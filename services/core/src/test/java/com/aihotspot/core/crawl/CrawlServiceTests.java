package com.aihotspot.core.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aihotspot.core.audit.AuditService;
import com.aihotspot.core.auth.AppUserPrincipal;
import com.aihotspot.core.messaging.OutboxStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class CrawlServiceTests {

    @Test
    void scheduledDispatchCreatesOneJobAndAdvancesEndpointCursor() throws Exception {
        CrawlMapper mapper = mock(CrawlMapper.class);
        OutboxStore outbox = mock(OutboxStore.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        UUID endpointId = UUID.randomUUID();
        Instant window = Instant.parse("2026-07-17T08:00:00Z");
        var endpoint = new CrawlMapper.EndpointForFetch(
                endpointId, UUID.randomUUID(), "https://example.com/feed.xml", "RSS",
                900, "ACTIVE", "{}", window);
        when(mapper.lockDueEndpoints(10)).thenReturn(List.of(endpoint));
        when(mapper.findJob(any())).thenAnswer(invocation -> job(invocation.getArgument(0), endpointId));

        List<CrawlMapper.FetchJobView> jobs = new CrawlService(
                mapper, outbox, mock(AuditService.class), objectMapper).dispatchDue(10);

        ArgumentCaptor<CrawlMapper.FetchJobInsert> inserted = ArgumentCaptor.forClass(CrawlMapper.FetchJobInsert.class);
        verify(mapper).insertJob(inserted.capture());
        assertThat(inserted.getValue().idempotencyKey())
                .isEqualTo("fetch:" + endpointId + ":scheduled:" + window);
        assertThat(jobs).hasSize(1);
        verify(mapper).scheduleNext(eq(endpointId), eq(inserted.getValue().id()), any(Instant.class));
        verify(outbox).append(any(UUID.class), eq("source.crawl.requested"), eq(1),
                eq("FetchJob"), eq(inserted.getValue().id()), anyString());
    }

    @Test
    void contentDeadLetterReplayResetsContentAndCreatesAuditedOutboxEvent() throws Exception {
        CrawlMapper mapper = mock(CrawlMapper.class);
        OutboxStore outbox = mock(OutboxStore.class);
        AuditService audit = mock(AuditService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UUID deadLetterId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID originalEventId = UUID.randomUUID();
        String idempotencyKey = "content:" + contentId + ":process:m3-v1";
        String payload = objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("eventId", originalEventId),
                Map.entry("eventType", "content.processing.requested"),
                Map.entry("eventVersion", 1),
                Map.entry("aggregateType", "ContentItem"),
                Map.entry("aggregateId", contentId),
                Map.entry("idempotencyKey", idempotencyKey),
                Map.entry("correlationId", UUID.randomUUID()),
                Map.entry("traceId", UUID.randomUUID()),
                Map.entry("occurredAt", Instant.now()),
                Map.entry("producer", "connector-worker"),
                Map.entry("payload", Map.of(
                        "contentItemId", contentId.toString(),
                        "rawEntryId", UUID.randomUUID().toString()))));
        when(mapper.findDeadLetterForReplay(deadLetterId)).thenReturn(
                new CrawlMapper.DeadLetterReplayView(
                        deadLetterId,
                        originalEventId,
                        "q.content.worker",
                        "content.processing.requested",
                        idempotencyKey,
                        "ContentItem",
                        contentId,
                        payload,
                        "PENDING"));
        when(mapper.resetContentForReplay(contentId)).thenReturn(1);
        when(mapper.markDeadLetterReplayed(eq(deadLetterId), any(UUID.class), any(UUID.class)))
                .thenReturn(1);
        var actor = new AppUserPrincipal(
                UUID.randomUUID(),
                "admin@example.com",
                "Admin",
                "not-used",
                "ACTIVE",
                List.of("ADMIN"),
                List.of("dead-letter:manage"));
        var service = new CrawlService(mapper, outbox, audit, objectMapper);

        CrawlService.DeadLetterReplayResponse response = service.replayDeadLetter(
                deadLetterId,
                actor,
                mock(HttpServletRequest.class));

        assertThat(response.aggregateType()).isEqualTo("ContentItem");
        assertThat(response.aggregateId()).isEqualTo(contentId);
        assertThat(response.status()).isEqualTo("REPLAYED");
        assertThat(response.fetchJob()).isNull();
        verify(mapper).resetContentForReplay(contentId);
        verify(outbox).append(
                eq(response.replayEventId()),
                eq("content.processing.requested"),
                eq(1),
                eq("ContentItem"),
                eq(contentId),
                anyString());
        verify(audit).record(
                eq(actor.id()),
                eq("DEAD_LETTER_REPLAYED"),
                eq("DEAD_LETTER"),
                eq(deadLetterId),
                anyString(),
                anyString(),
                any(HttpServletRequest.class));
    }

    private CrawlMapper.FetchJobView job(UUID id, UUID endpointId) {
        Instant now = Instant.now();
        return new CrawlMapper.FetchJobView(
                id, endpointId, "Feed", "Source", "SCHEDULED", "key", "QUEUED", "PENDING",
                0, 4, null, 0, 0, 0, null, null, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, now, now);
    }
}
