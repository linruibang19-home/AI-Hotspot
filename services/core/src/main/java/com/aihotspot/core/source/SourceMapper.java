package com.aihotspot.core.source;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SourceMapper {

    int insertEntity(SourceEntity entity);
    int insertEntityIfAbsent(SourceEntity entity);
    int insertEndpoint(SourceEndpoint endpoint);
    int insertEndpointIfAbsent(SourceEndpoint endpoint);
    UUID findIdBySlug(@Param("slug") String slug);
    SourceDetail findById(@Param("id") UUID id);
    EndpointDetail findEndpointById(@Param("id") UUID id);
    List<SourceSummary> list(@Param("status") String status, @Param("query") String query, @Param("limit") int limit);
    List<SourceSummary> listBySourceId(@Param("sourceId") UUID sourceId);
    SourceMetrics metrics();
    int activateEntity(@Param("id") UUID id);
    int updateEndpointStatus(@Param("id") UUID id, @Param("status") String status, @Param("version") long version);
    int updateProbe(
            @Param("id") UUID id,
            @Param("healthStatus") String healthStatus,
            @Param("httpStatus") Integer httpStatus,
            @Param("latencyMs") long latencyMs,
            @Param("successful") boolean successful,
            @Param("version") long version);

    record SourceEntity(
            UUID id, String name, String slug, String entityType, String countryCode,
            String officialLevel, java.math.BigDecimal authorityScore, String websiteUrl,
            String logoUrl, String status, long version, UUID createdBy) {}

    record SourceEndpoint(
            UUID id, UUID sourceEntityId, String name, String url, String normalizedUrl,
            String endpointType, String connectorType, String language, int pollingIntervalSeconds,
            String displayPolicy, String indexPolicy, java.math.BigDecimal authorityOverride,
            String configJson, String credentialRef, String status, String healthStatus,
            int failureCount, long version, UUID createdBy, String catalogKind, String catalogKey) {}

    record SourceSummary(
            UUID id, String name, String slug, String entityType, String officialLevel,
            java.math.BigDecimal authorityScore, String sourceStatus, UUID endpointId,
            String endpointName, String endpointType, String connectorType, String endpointUrl,
            String endpointStatus, String catalogKind,
            String healthStatus, Instant lastSuccessAt, Instant lastFailureAt, long endpointVersion,
            int lastFetchItemCount, long todayContentCount, Instant updatedAt) {}

    record SourceDetail(
            UUID id, String name, String slug, String entityType, String countryCode,
            String officialLevel, java.math.BigDecimal authorityScore, String websiteUrl,
            String logoUrl, String status, long version, UUID createdBy, Instant createdAt,
            Instant updatedAt) {}

    record EndpointDetail(
            UUID id, UUID sourceEntityId, String name, String url, String normalizedUrl,
            String endpointType, String connectorType, String language, int pollingIntervalSeconds,
            String displayPolicy, String indexPolicy, java.math.BigDecimal authorityOverride,
            String configJson, String credentialRef, String status, String healthStatus,
            Instant lastSuccessAt, Instant lastFailureAt, int failureCount, Integer lastProbeStatus,
            Long lastProbeLatencyMs, long version, UUID createdBy, String catalogKind,
            String catalogKey, Instant createdAt, Instant updatedAt) {}

    record SourceMetrics(long totalEndpoints, long activeEndpoints, long healthyEndpoints,
                         long attentionEndpoints, long productionEndpoints, long testEndpoints) {}
}
