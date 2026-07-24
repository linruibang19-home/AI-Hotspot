package com.aihotspot.core.crawl;

import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.audit.AuditService;
import com.aihotspot.core.auth.AppUserPrincipal;
import com.aihotspot.core.messaging.OutboxStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class CrawlService {

    private static final Set<String> AVAILABLE_CONNECTORS = Set.of(
            "RSS", "ATOM", "WEBSITE", "SITEMAP", "GITHUB",
            "HUGGING_FACE", "ARXIV", "OPENREVIEW", "HACKER_NEWS");

    private static final Set<String> JOB_STATUSES = Set.of(
            "QUEUED", "RUNNING", "WAITING_RETRY", "SUCCEEDED", "FAILED", "DEAD_LETTERED", "CANCELLED");
    private static final Set<String> REPLAY_STATUSES = Set.of("PENDING", "REPLAYED", "IGNORED");

    private final CrawlMapper mapper;
    private final OutboxStore outbox;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public CrawlService(CrawlMapper mapper, OutboxStore outbox, AuditService audit, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<CrawlMapper.FetchJobView> dispatchDue(int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), 100);
        List<CrawlMapper.FetchJobView> created = new ArrayList<>();
        for (CrawlMapper.EndpointForFetch endpoint : mapper.lockDueEndpoints(limit)) {
            Instant window = endpoint.nextFetchAt() == null
                    ? Instant.now().truncatedTo(ChronoUnit.SECONDS)
                    : endpoint.nextFetchAt();
            UUID jobId = createJob(endpoint, "SCHEDULED", window, null, null);
            mapper.scheduleNext(endpoint.id(), jobId, Instant.now().plusSeconds(endpoint.pollingIntervalSeconds()));
            created.add(mapper.findJob(jobId));
        }
        return created;
    }

    @Transactional
    public CrawlMapper.FetchJobView trigger(
            UUID endpointId, AppUserPrincipal actor, HttpServletRequest request) {
        CrawlMapper.EndpointForFetch endpoint = requireFetchableEndpoint(endpointId);
        UUID jobId = createJob(
                endpoint,
                "MANUAL",
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                null,
                actor.id());
        mapper.scheduleNext(endpoint.id(), jobId, Instant.now().plusSeconds(endpoint.pollingIntervalSeconds()));
        audit.record(actor.id(), "FETCH_JOB_TRIGGERED", "FETCH_JOB", jobId, null,
                json(Map.of("endpointId", endpoint.id(), "triggerType", "MANUAL")), request);
        return mapper.findJob(jobId);
    }

    public List<CrawlMapper.FetchJobView> listJobs(String status, int requestedLimit) {
        String safeStatus = normalizeStatus(status, JOB_STATUSES, "采集任务状态不正确");
        return mapper.listJobs(safeStatus, Math.min(Math.max(requestedLimit, 1), 100));
    }

    public CrawlMapper.FetchJobView getJob(UUID id) {
        CrawlMapper.FetchJobView view = mapper.findJob(id);
        if (view == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FETCH_JOB_NOT_FOUND", "采集任务不存在");
        }
        return view;
    }

    public List<CrawlMapper.DeadLetterView> listDeadLetters(String status, int requestedLimit) {
        String safeStatus = normalizeStatus(status, REPLAY_STATUSES, "死信状态不正确");
        return mapper.listDeadLetters(safeStatus, Math.min(Math.max(requestedLimit, 1), 100));
    }

    @Transactional
    public DeadLetterReplayResponse replayDeadLetter(
            UUID deadLetterId, AppUserPrincipal actor, HttpServletRequest request) {
        CrawlMapper.DeadLetterReplayView deadLetter = mapper.findDeadLetterForReplay(deadLetterId);
        if (deadLetter == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEAD_LETTER_NOT_FOUND", "死信记录不存在");
        }
        if (!"PENDING".equals(deadLetter.replayStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DEAD_LETTER_ALREADY_HANDLED", "死信已经处理");
        }
        if (deadLetter.aggregateId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPLAY_NOT_SUPPORTED", "死信缺少可回放的聚合标识");
        }

        UUID eventId = UUID.randomUUID();
        CrawlMapper.FetchJobView job = null;
        String payloadJson;
        if ("FetchJob".equals(deadLetter.aggregateType())
                && "source.crawl.requested".equals(deadLetter.eventType())) {
            job = getJob(deadLetter.aggregateId());
            if (mapper.resetJobForReplay(job.id()) != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "FETCH_JOB_NOT_DEAD_LETTERED", "关联任务当前不可回放");
            }
            payloadJson = crawlReplayEnvelope(deadLetter, job, eventId);
        } else if ("ContentItem".equals(deadLetter.aggregateType())
                && "content.processing.requested".equals(deadLetter.eventType())
                && "q.content.worker".equals(deadLetter.queueName())) {
            if (mapper.resetContentForReplay(deadLetter.aggregateId()) != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "CONTENT_ITEM_NOT_FAILED", "关联内容当前不可回放");
            }
            payloadJson = contentReplayEnvelope(deadLetter, eventId);
        } else {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPLAY_NOT_SUPPORTED", "该死信类型暂不支持回放");
        }

        outbox.append(
                eventId,
                deadLetter.eventType(),
                1,
                deadLetter.aggregateType(),
                deadLetter.aggregateId(),
                payloadJson);
        if (mapper.markDeadLetterReplayed(deadLetterId, actor.id(), eventId) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "DEAD_LETTER_REPLAY_CONFLICT", "死信回放状态冲突");
        }
        audit.record(actor.id(), "DEAD_LETTER_REPLAYED", "DEAD_LETTER", deadLetterId,
                json(Map.of("status", "PENDING", "aggregateType", deadLetter.aggregateType(),
                        "aggregateId", deadLetter.aggregateId())),
                json(Map.of("status", "REPLAYED", "replayEventId", eventId,
                        "aggregateType", deadLetter.aggregateType(), "aggregateId", deadLetter.aggregateId())),
                request);
        return new DeadLetterReplayResponse(
                deadLetter.aggregateType(),
                deadLetter.aggregateId(),
                eventId,
                "REPLAYED",
                job == null ? null : mapper.findJob(job.id()));
    }

    private String crawlReplayEnvelope(
            CrawlMapper.DeadLetterReplayView deadLetter,
            CrawlMapper.FetchJobView job,
            UUID eventId) {
        Map<String, Object> envelope = Map.ofEntries(
                Map.entry("eventId", eventId),
                Map.entry("eventType", deadLetter.eventType()),
                Map.entry("eventVersion", 1),
                Map.entry("aggregateType", deadLetter.aggregateType()),
                Map.entry("aggregateId", job.id()),
                Map.entry("idempotencyKey", deadLetter.idempotencyKey()),
                Map.entry("correlationId", job.correlationId()),
                Map.entry("traceId", job.traceId()),
                Map.entry("occurredAt", Instant.now()),
                Map.entry("producer", "core-api"),
                Map.entry("payload", Map.of("fetchJobId", job.id(), "endpointId", job.endpointId())));
        return json(envelope);
    }

    @SuppressWarnings("unchecked")
    private String contentReplayEnvelope(CrawlMapper.DeadLetterReplayView deadLetter, UUID eventId) {
        try {
            Map<String, Object> original = objectMapper.readValue(deadLetter.payloadJson(), Map.class);
            Object payload = original.get("payload");
            if (!(payload instanceof Map<?, ?> payloadMap)
                    || !deadLetter.aggregateId().toString().equals(String.valueOf(payloadMap.get("contentItemId")))) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "DEAD_LETTER_PAYLOAD_INVALID",
                        "内容死信载荷与聚合标识不一致");
            }
            Map<String, Object> replay = new LinkedHashMap<>(original);
            replay.put("eventId", eventId);
            replay.put("eventType", deadLetter.eventType());
            replay.put("eventVersion", 1);
            replay.put("aggregateType", deadLetter.aggregateType());
            replay.put("aggregateId", deadLetter.aggregateId());
            replay.put("idempotencyKey", deadLetter.idempotencyKey());
            replay.put("occurredAt", Instant.now());
            replay.put("producer", "core-api");
            return json(replay);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DEAD_LETTER_PAYLOAD_INVALID",
                    "内容死信载荷无法解析");
        }
    }

    private UUID createJob(
            CrawlMapper.EndpointForFetch endpoint,
            String triggerType,
            Instant scheduledWindow,
            UUID replayOf,
            UUID requestedBy) {
        UUID jobId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        String idempotencyKey = "MANUAL".equals(triggerType)
                ? "fetch:" + endpoint.id() + ":manual:" + jobId
                : "fetch:" + endpoint.id() + ":scheduled:" + scheduledWindow;
        mapper.insertJob(new CrawlMapper.FetchJobInsert(
                jobId, endpoint.id(), triggerType, scheduledWindow, idempotencyKey,
                "QUEUED", 4, correlationId, traceId, replayOf, requestedBy));
        appendCrawlEvent(UUID.randomUUID(), jobId, endpoint.id(), idempotencyKey, correlationId, traceId);
        return jobId;
    }

    private void appendCrawlEvent(
            UUID eventId,
            UUID jobId,
            UUID endpointId,
            String idempotencyKey,
            UUID correlationId,
            UUID traceId) {
        Map<String, Object> envelope = Map.ofEntries(
                Map.entry("eventId", eventId),
                Map.entry("eventType", "source.crawl.requested"),
                Map.entry("eventVersion", 1),
                Map.entry("aggregateType", "FetchJob"),
                Map.entry("aggregateId", jobId),
                Map.entry("idempotencyKey", idempotencyKey),
                Map.entry("correlationId", correlationId),
                Map.entry("traceId", traceId),
                Map.entry("occurredAt", Instant.now()),
                Map.entry("producer", "core-api"),
                Map.entry("payload", Map.of("fetchJobId", jobId, "endpointId", endpointId)));
        outbox.append(eventId, "source.crawl.requested", 1, "FetchJob", jobId, json(envelope));
    }

    private CrawlMapper.EndpointForFetch requireFetchableEndpoint(UUID endpointId) {
        CrawlMapper.EndpointForFetch endpoint = mapper.findEndpoint(endpointId);
        if (endpoint == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SOURCE_ENDPOINT_NOT_FOUND", "信源入口不存在");
        }
        if (!"ACTIVE".equals(endpoint.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_ENDPOINT_NOT_ACTIVE", "只允许抓取 ACTIVE 入口");
        }
        if (!AVAILABLE_CONNECTORS.contains(endpoint.endpointType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTOR_NOT_AVAILABLE", "该 Connector 当前不可采集");
        }
        return endpoint;
    }

    private String normalizeStatus(String value, Set<String> allowed, String message) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS", message);
        }
        return normalized;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化采集事件", exception);
        }
    }

    public record DeadLetterReplayResponse(
            String aggregateType,
            UUID aggregateId,
            UUID replayEventId,
            String status,
            CrawlMapper.FetchJobView fetchJob) {}
}
