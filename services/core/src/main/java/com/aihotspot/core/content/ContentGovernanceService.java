package com.aihotspot.core.content;

import com.aihotspot.core.api.ApiException;
import com.aihotspot.core.audit.AuditService;
import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ContentGovernanceService {
    private static final Set<String> ADMISSION = Set.of("PENDING", "PASSED", "FAILED", "REVIEW_REQUIRED");
    private static final Set<String> FACT = Set.of("CONFIRMED", "UNCONFIRMED", "DEBUNKED");
    private static final Set<String> ACTIONS = Set.of("APPROVE", "REJECT", "MARK_UNCONFIRMED", "DEBUNK", "TAKEDOWN", "RESTORE");
    private static final Set<String> RELATIONS = Set.of("RELATED", "FOLLOW_UP", "CONTRADICTS");
    private static final Set<String> TICKET_TYPES = Set.of("REVIEW", "CORRECTION", "TAKEDOWN", "COPYRIGHT");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");

    private final ContentGovernanceMapper mapper;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public ContentGovernanceService(ContentGovernanceMapper mapper, AuditService audit, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public ContentGovernanceMapper.ContentMetrics metrics() { return mapper.metrics(); }

    public List<ContentGovernanceMapper.ContentReviewItem> list(String status, String factStatus, String query, int limit) {
        String safeStatus = optional(status, ADMISSION, "INVALID_ADMISSION_STATUS");
        String safeFact = optional(factStatus, FACT, "INVALID_FACT_STATUS");
        return mapper.list(safeStatus, safeFact, blank(query), Math.min(Math.max(limit, 1), 100));
    }

    public ContentGovernanceMapper.ContentReviewItem get(UUID id) {
        var item = mapper.findById(id);
        if (item == null) throw new ApiException(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND", "内容不存在");
        return item;
    }

    @Transactional
    public ContentGovernanceMapper.ContentReviewItem govern(UUID id, String action, String reason, long version,
                                                             AppUserPrincipal actor, HttpServletRequest request) {
        if (!ACTIONS.contains(action)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GOVERNANCE_ACTION", "治理动作不正确");
        if (reason == null || reason.strip().length() < 4) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REASON_REQUIRED", "必须填写至少 4 个字符的操作理由");
        var before = get(id);
        if (before.version() != version) throw conflict();
        String publication = before.publicationStatus();
        String visibility = before.visibility();
        String admission = before.admissionStatus();
        String fact = before.factStatus();
        boolean featured = before.featured();
        if (Set.of("APPROVE", "RESTORE").contains(action) && before.duplicate()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_CANNOT_PUBLISH", "重复内容不能直接公开，请改为维护主条目");
        }
        if ("APPROVE".equals(action) && "DEBUNKED".equals(before.factStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEBUNK_REVIEW_REQUIRED", "已证伪内容必须先完成事实更正");
        }
        switch (action) {
            case "APPROVE" -> { admission = "PASSED"; publication = "PUBLISHED"; visibility = "PUBLIC"; }
            case "REJECT" -> { admission = "FAILED"; publication = "REJECTED"; visibility = "PRIVATE"; featured = false; }
            case "MARK_UNCONFIRMED" -> { fact = "UNCONFIRMED"; featured = false; }
            case "DEBUNK" -> { fact = "DEBUNKED"; publication = "UNPUBLISHED"; visibility = "PRIVATE"; featured = false; }
            case "TAKEDOWN" -> { publication = "UNPUBLISHED"; visibility = "PRIVATE"; featured = false; }
            case "RESTORE" -> {
                if ("DEBUNKED".equals(fact)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEBUNK_REVIEW_REQUIRED", "已证伪内容必须先更正事实状态");
                admission = "PASSED"; publication = "PUBLISHED"; visibility = "PUBLIC";
            }
            default -> throw new IllegalStateException("unreachable");
        }
        if (mapper.govern(id, publication, visibility, admission, fact, featured, version) != 1) throw conflict();
        var after = get(id);
        audit.record(actor.id(), "CONTENT_" + action, "CONTENT_ITEM", id,
                json(Map.of("item", before, "reason", reason.strip())),
                json(Map.of("item", after, "reason", reason.strip())), request);
        return after;
    }

    public List<ContentGovernanceMapper.EventView> events(int limit) {
        return mapper.listEvents(Math.min(Math.max(limit, 1), 100));
    }

    @Transactional
    public void addRelation(UUID eventId, UUID contentId, String relationType, BigDecimal confidence,
                            AppUserPrincipal actor, HttpServletRequest request) {
        if (mapper.findEventById(eventId) == null) throw new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "事件不存在");
        get(contentId);
        if (!RELATIONS.contains(relationType)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RELATION", "关联类型不正确");
        BigDecimal safe = confidence == null ? BigDecimal.valueOf(80) : confidence;
        if (safe.signum() < 0 || safe.compareTo(BigDecimal.valueOf(100)) > 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CONFIDENCE", "关联置信度必须在 0 到 100 之间");
        mapper.addRelation(contentId, eventId, relationType, safe, actor.id());
        audit.record(actor.id(), "EVENT_RELATION_UPSERTED", "EVENT_CLUSTER", eventId, null,
                json(Map.of("contentId", contentId, "relationType", relationType, "confidence", safe)), request);
    }

    @Transactional
    public void removeRelation(UUID eventId, UUID contentId, AppUserPrincipal actor, HttpServletRequest request) {
        if (mapper.removeRelation(contentId, eventId) != 1) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRIMARY_RELATION_PROTECTED", "主事件关联不可删除，或关联不存在");
        audit.record(actor.id(), "EVENT_RELATION_REMOVED", "EVENT_CLUSTER", eventId,
                json(Map.of("contentId", contentId)), null, request);
    }

    @Transactional
    public UUID createTicket(UUID contentId, UUID eventId, String type, String priority, String reason,
                             String email, AppUserPrincipal actor) {
        if (!TICKET_TYPES.contains(type)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TICKET_TYPE", "反馈类型不正确");
        if (!PRIORITIES.contains(priority)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRIORITY", "优先级不正确");
        if (reason == null || reason.strip().length() < 8) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FEEDBACK_TOO_SHORT", "请至少填写 8 个字符的反馈说明");
        if (contentId != null) get(contentId);
        if (eventId != null && mapper.findEventById(eventId) == null) throw new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "事件不存在");
        if (contentId == null && eventId == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TICKET_TARGET_REQUIRED", "反馈必须关联内容或事件");
        UUID id = UUID.randomUUID();
        mapper.insertTicket(new ContentGovernanceMapper.TicketEntry(id, contentId, eventId, type, priority,
                reason.strip(), blank(email), actor == null ? null : actor.id()));
        return id;
    }

    public List<ContentGovernanceMapper.TicketView> tickets(String status, int limit) {
        String safe = optional(status, Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "REJECTED"), "INVALID_TICKET_STATUS");
        return mapper.listTickets(safe, Math.min(Math.max(limit, 1), 100));
    }

    @Transactional
    public ContentGovernanceMapper.TicketView resolveTicket(UUID id, String status, String resolution,
                                                             AppUserPrincipal actor, HttpServletRequest request) {
        if (!Set.of("RESOLVED", "REJECTED").contains(status)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TICKET_STATUS", "只能解决或驳回工单");
        if (resolution == null || resolution.strip().length() < 4) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOLUTION_REQUIRED", "必须填写处理结果");
        var before = mapper.findTicketById(id);
        if (before == null) throw new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单不存在");
        if (mapper.resolveTicket(id, status, resolution.strip(), actor.id()) != 1) throw conflict();
        var after = mapper.findTicketById(id);
        audit.record(actor.id(), "CONTENT_TICKET_" + status, "CONTENT_TICKET", id, json(before), json(after), request);
        return after;
    }

    private String optional(String value, Set<String> allowed, String code) {
        String safe = blank(value);
        if (safe != null && !allowed.contains(safe)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, "筛选状态不正确");
        return safe;
    }
    private String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "数据已被其他操作更新，请刷新后重试"); }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("无法生成审计数据", exception); }
    }
}
