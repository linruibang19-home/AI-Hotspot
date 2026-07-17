package com.aihotspot.core.content;

import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/content")
public class ContentGovernanceController {
    private final ContentGovernanceService service;
    public ContentGovernanceController(ContentGovernanceService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('content:read')")
    public ContentListResponse list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String factStatus,
                                    @RequestParam(required = false) String query,
                                    @RequestParam(defaultValue = "50") int limit) {
        return new ContentListResponse(service.metrics(), service.list(status, factStatus, query, limit));
    }
    @PostMapping("/{id}/actions")
    @PreAuthorize("hasAuthority('content:review')")
    public ContentGovernanceMapper.ContentReviewItem govern(@PathVariable UUID id,
            @Valid @RequestBody GovernanceRequest body, @AuthenticationPrincipal AppUserPrincipal actor,
            HttpServletRequest request) {
        return service.govern(id, body.action(), body.reason(), body.version(), actor, request);
    }
    @GetMapping("/events")
    @PreAuthorize("hasAuthority('content:read')")
    public List<ContentGovernanceMapper.EventView> events(@RequestParam(defaultValue = "50") int limit) {
        return service.events(limit);
    }
    @PostMapping("/events/{eventId}/relations")
    @PreAuthorize("hasAuthority('event:manage')")
    public void addRelation(@PathVariable UUID eventId, @Valid @RequestBody RelationRequest body,
            @AuthenticationPrincipal AppUserPrincipal actor, HttpServletRequest request) {
        service.addRelation(eventId, body.contentId(), body.relationType(), body.confidence(), actor, request);
    }
    @DeleteMapping("/events/{eventId}/relations/{contentId}")
    @PreAuthorize("hasAuthority('event:manage')")
    public void removeRelation(@PathVariable UUID eventId, @PathVariable UUID contentId,
            @AuthenticationPrincipal AppUserPrincipal actor, HttpServletRequest request) {
        service.removeRelation(eventId, contentId, actor, request);
    }
    @GetMapping("/tickets")
    @PreAuthorize("hasAuthority('ticket:manage')")
    public List<ContentGovernanceMapper.TicketView> tickets(@RequestParam(required = false) String status,
                                                             @RequestParam(defaultValue = "50") int limit) {
        return service.tickets(status, limit);
    }
    @PostMapping("/tickets/{id}/resolve")
    @PreAuthorize("hasAuthority('ticket:manage')")
    public ContentGovernanceMapper.TicketView resolve(@PathVariable UUID id,
            @Valid @RequestBody ResolveTicketRequest body, @AuthenticationPrincipal AppUserPrincipal actor,
            HttpServletRequest request) {
        return service.resolveTicket(id, body.status(), body.resolution(), actor, request);
    }

    public record ContentListResponse(ContentGovernanceMapper.ContentMetrics metrics,
                                      List<ContentGovernanceMapper.ContentReviewItem> items) {}
    public record GovernanceRequest(@NotBlank String action, @NotBlank @Size(max = 1000) String reason,
                                    long version) {}
    public record RelationRequest(@NotNull UUID contentId, @NotBlank String relationType,
            @DecimalMin("0") @DecimalMax("100") BigDecimal confidence) {}
    public record ResolveTicketRequest(@NotBlank String status, @NotBlank @Size(max = 2000) String resolution) {}
}
