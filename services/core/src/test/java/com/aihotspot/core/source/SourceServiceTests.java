package com.aihotspot.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aihotspot.core.audit.AuditService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SourceServiceTests {

    @Test
    void detailLoadsEndpointsBySourceIdInsteadOfAFreeTextSearch() {
        SourceMapper mapper = mock(SourceMapper.class);
        UUID sourceId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        Instant now = Instant.now();
        SourceMapper.SourceDetail source = new SourceMapper.SourceDetail(
                sourceId, "Example", "example", "MEDIA", "US", "OFFICIAL",
                BigDecimal.valueOf(80), "https://example.com", null, "DRAFT", 0,
                UUID.randomUUID(), now, now);
        SourceMapper.SourceSummary endpoint = new SourceMapper.SourceSummary(
                sourceId, "Example", "example", "MEDIA", "OFFICIAL", BigDecimal.valueOf(80),
                "DRAFT", endpointId, "Website", "WEBSITE", "https://example.com", "ACTIVE",
                "HEALTHY", now, null, 2, now);
        when(mapper.findById(sourceId)).thenReturn(source);
        when(mapper.listBySourceId(sourceId)).thenReturn(List.of(endpoint));

        SourceService service = new SourceService(mapper, mock(AuditService.class), mock(ObjectMapper.class));
        SourceService.SourceView result = service.get(sourceId);

        assertThat(result.source()).isEqualTo(source);
        assertThat(result.endpoints()).containsExactly(endpoint);
        verify(mapper).listBySourceId(sourceId);
    }
}
