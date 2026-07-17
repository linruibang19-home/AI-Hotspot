package com.aihotspot.core.crawl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CrawlMapper {

    List<EndpointForFetch> lockDueEndpoints(@Param("limit") int limit);

    EndpointForFetch findEndpoint(@Param("id") UUID id);

    void insertJob(FetchJobInsert job);

    int scheduleNext(
            @Param("endpointId") UUID endpointId,
            @Param("jobId") UUID jobId,
            @Param("nextFetchAt") Instant nextFetchAt);

    List<FetchJobView> listJobs(@Param("status") String status, @Param("limit") int limit);

    FetchJobView findJob(@Param("id") UUID id);

    List<DeadLetterView> listDeadLetters(@Param("status") String status, @Param("limit") int limit);

    DeadLetterView findDeadLetter(@Param("id") UUID id);

    int resetJobForReplay(@Param("id") UUID id);

    int markDeadLetterReplayed(
            @Param("id") UUID id,
            @Param("actorId") UUID actorId,
            @Param("replayEventId") UUID replayEventId);

    record EndpointForFetch(
            UUID id,
            UUID sourceEntityId,
            String url,
            String endpointType,
            int pollingIntervalSeconds,
            String status,
            String configJson,
            Instant nextFetchAt) {}

    record FetchJobInsert(
            UUID id,
            UUID endpointId,
            String triggerType,
            Instant scheduledWindow,
            String idempotencyKey,
            String status,
            int maxAttempts,
            UUID correlationId,
            UUID traceId,
            UUID replayOfJobId,
            UUID requestedBy) {}

    record FetchJobView(
            UUID id,
            UUID endpointId,
            String endpointName,
            String sourceName,
            String triggerType,
            String idempotencyKey,
            String status,
            int attemptCount,
            int maxAttempts,
            Integer httpStatus,
            int artifactCount,
            int discoveredCount,
            int newEntryCount,
            String errorCode,
            String lastError,
            UUID correlationId,
            UUID traceId,
            UUID replayOfJobId,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt) {}

    record DeadLetterView(
            UUID id,
            UUID originalEventId,
            String queueName,
            String eventType,
            String idempotencyKey,
            String aggregateType,
            UUID aggregateId,
            int failureCount,
            String lastError,
            String replayStatus,
            UUID replayedBy,
            UUID replayEventId,
            Instant firstFailedAt,
            Instant lastFailedAt,
            Instant createdAt) {}
}

