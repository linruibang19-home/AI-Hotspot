package com.aihotspot.core.audit;

import com.aihotspot.core.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditMapper mapper;

    public AuditService(AuditMapper mapper) {
        this.mapper = mapper;
    }

    public UUID record(
            UUID actorId,
            String action,
            String targetType,
            UUID targetId,
            String beforeData,
            String afterData,
            HttpServletRequest request) {
        UUID auditId = UUID.randomUUID();
        mapper.insert(new AuditMapper.AuditEntry(
                auditId,
                actorId,
                actorId == null ? "SYSTEM" : "USER",
                action,
                targetType,
                targetId,
                beforeData,
                afterData,
                clientIp(request),
                request == null ? null : request.getHeader("User-Agent"),
                request == null ? null : String.valueOf(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)),
                request == null ? null : request.getHeader("X-Trace-ID")));
        return auditId;
    }

    public List<AuditMapper.AuditView> findByTarget(String targetType, UUID targetId, int limit) {
        return mapper.findByTarget(targetType, targetId, Math.min(Math.max(limit, 1), 100));
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",", 2)[0].trim();
        return request.getRemoteAddr();
    }
}
