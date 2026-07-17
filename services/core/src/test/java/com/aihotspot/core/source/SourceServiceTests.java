package com.aihotspot.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aihotspot.core.audit.AuditService;
import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                "DRAFT", endpointId, "Website", "WEBSITE", "WEBSITE", "https://example.com", "ACTIVE",
                "USER_MANAGED", "HEALTHY", now, null, 2, 0, 0, now);
        when(mapper.findById(sourceId)).thenReturn(source);
        when(mapper.listBySourceId(sourceId)).thenReturn(List.of(endpoint));

        SourceService service = new SourceService(mapper, mock(AuditService.class), mock(ObjectMapper.class));
        SourceService.SourceView result = service.get(sourceId);

        assertThat(result.source()).isEqualTo(source);
        assertThat(result.endpoints()).containsExactly(endpoint);
        verify(mapper).listBySourceId(sourceId);
    }

    @Test
    void rejectsOutOfRangeConnectorConfigurationBeforePersistence() {
        SourceService service = new SourceService(
                mock(SourceMapper.class), mock(AuditService.class), mock(ObjectMapper.class));
        SourceService.CreateSource command = new SourceService.CreateSource(
                "Example", "example", "MEDIA", "US", "OFFICIAL", BigDecimal.valueOf(80),
                "https://example.com", null, "Feed", "https://example.com/feed.xml", "RSS", "en",
                900, "SUMMARY_ONLY", "PUBLIC_RAG", null,
                Map.of("maxItems", 201, "autoPublish", true));
        AppUserPrincipal actor = new AppUserPrincipal(
                UUID.randomUUID(), "admin@example.com", "Admin", "hash", "ACTIVE",
                List.of("ADMIN"), List.of("source:write"));

        assertThatThrownBy(() -> service.create(command, actor, mock(HttpServletRequest.class)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("INVALID_SOURCE");
                    assertThat(exception.getMessage()).contains("maxItems");
                });
    }

    @Test
    void xConnectorContractIsVisibleButCannotBeProbedOrActivated() {
        SourceMapper mapper = mock(SourceMapper.class);
        UUID endpointId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();
        when(mapper.findEndpointById(endpointId)).thenReturn(new SourceMapper.EndpointDetail(
                endpointId, sourceId, "Reserved X", "https://x.com/example", "https://x.com/example",
                "X", "X", "en", 3600, "LINK_ONLY", "NO_INDEX", null, "{}", null,
                "DRAFT", "UNKNOWN", null, null, 0, null, null, 3, actorId,
                "USER_MANAGED", null, now, now));
        SourceService service = new SourceService(mapper, mock(AuditService.class), mock(ObjectMapper.class));
        AppUserPrincipal actor = new AppUserPrincipal(
                actorId, "admin@example.com", "Admin", "hash", "ACTIVE",
                List.of("ADMIN"), List.of("source:probe", "source:activate"));

        assertThat(service.connectorSchemas().get("X").toString()).contains("RESERVED");
        assertThatThrownBy(() -> service.probe(endpointId, 3, actor, mock(HttpServletRequest.class)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo("CONNECTOR_RESERVED"));
        assertThatThrownBy(() -> service.changeStatus(endpointId, "ACTIVE", 3, actor, mock(HttpServletRequest.class)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo("CONNECTOR_RESERVED"));
    }
}
