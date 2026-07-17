package com.aihotspot.core.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditMapper {

    void insert(AuditEntry entry);

    List<AuditView> findByTarget(
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("limit") int limit);

    record AuditEntry(
            UUID id,
            UUID actorId,
            String actorType,
            String action,
            String targetType,
            UUID targetId,
            String beforeData,
            String afterData,
            String clientIp,
            String userAgent,
            String correlationId,
            String traceId) {}

    record AuditView(
            UUID id,
            UUID actorId,
            String actorType,
            String action,
            String targetType,
            UUID targetId,
            String beforeData,
            String afterData,
            String correlationId,
            Instant createdAt) {}
}
