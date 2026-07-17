package com.aihotspot.core.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aihotspot.core.audit.AuditService;
import com.aihotspot.core.messaging.OutboxStore;
import java.time.Instant;
import java.util.List;
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

    private CrawlMapper.FetchJobView job(UUID id, UUID endpointId) {
        Instant now = Instant.now();
        return new CrawlMapper.FetchJobView(
                id, endpointId, "Feed", "Source", "SCHEDULED", "key", "QUEUED",
                0, 4, null, 0, 0, 0, null, null, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, now, now);
    }
}
