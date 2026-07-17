package com.aihotspot.core.crawl;

import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.audit.AuditService;
import com.aihotspot.core.auth.AppUserPrincipal;
import com.aihotspot.core.messaging.OutboxStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
    public CrawlMapper.FetchJobView replayDeadLetter(
            UUID deadLetterId, AppUserPrincipal actor, HttpServletRequest request) {
        CrawlMapper.DeadLetterView deadLetter = mapper.findDeadLetter(deadLetterId);
        if (deadLetter == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEAD_LETTER_NOT_FOUND", "死信记录不存在");
        }
        if (!"PENDING".equals(deadLetter.replayStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DEAD_LETTER_ALREADY_HANDLED", "死信已经处理");
        }
        if (!"FetchJob".equals(deadLetter.aggregateType()) || deadLetter.aggregateId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPLAY_NOT_SUPPORTED", "M3 只支持采集任务死信回放");
        }
        CrawlMapper.FetchJobView job = getJob(deadLetter.aggregateId());
        if (mapper.resetJobForReplay(job.id()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "FETCH_JOB_NOT_DEAD_LETTERED", "关联任务当前不可回放");
        }
        UUID eventId = UUID.randomUUID();
        appendCrawlEvent(eventId, job.id(), job.endpointId(), job.idempotencyKey(), job.correlationId(), job.traceId());
        if (mapper.markDeadLetterReplayed(deadLetterId, actor.id(), eventId) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "DEAD_LETTER_REPLAY_CONFLICT", "死信回放状态冲突");
        }
        audit.record(actor.id(), "DEAD_LETTER_REPLAYED", "DEAD_LETTER", deadLetterId,
                json(Map.of("status", "PENDING", "fetchJobId", job.id())),
                json(Map.of("status", "REPLAYED", "replayEventId", eventId, "fetchJobId", job.id())), request);
        return mapper.findJob(job.id());
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
        if (!Set.of("RSS", "ATOM").contains(endpoint.endpointType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTOR_NOT_AVAILABLE", "M3 只支持 RSS/Atom 抓取");
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
}
