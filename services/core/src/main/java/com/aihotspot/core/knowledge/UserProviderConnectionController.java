package com.aihotspot.core.knowledge;

import com.aihotspot.core.audit.AuditService;
import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/me/ai")
@PreAuthorize("hasAuthority('research:use')")
public class UserProviderConnectionController {
    private final UserProviderConnectionService connections;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public UserProviderConnectionController(UserProviderConnectionService connections, AuditService audit,
                                            ObjectMapper objectMapper) {
        this.connections = connections;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/connections")
    public List<UserProviderConnectionService.UserConnectionView> connections(
            @AuthenticationPrincipal AppUserPrincipal user) {
        return connections.connections(user.id());
    }

    @GetMapping("/usage")
    public UserProviderConnectionService.UsageView usage(
            @AuthenticationPrincipal AppUserPrincipal user) {
        return connections.usage(user.id());
    }

    @PostMapping("/connections")
    public UserProviderConnectionService.UserConnectionView create(
            @RequestBody UserProviderConnectionService.SaveUserConnection body,
            @AuthenticationPrincipal AppUserPrincipal user,
            HttpServletRequest request) {
        var created = connections.create(user, body);
        audit.record(user.id(), "USER_PROVIDER_CONNECTION_CREATED", "PROVIDER_CONNECTION",
                created.id(), null, safeSummary(created), request);
        return created;
    }

    @PutMapping("/connections/{connectionId}")
    public UserProviderConnectionService.UserConnectionView update(
            @PathVariable UUID connectionId,
            @RequestBody UserProviderConnectionService.SaveUserConnection body,
            @AuthenticationPrincipal AppUserPrincipal user,
            HttpServletRequest request) {
        var updated = connections.update(user.id(), connectionId, body);
        audit.record(user.id(), "USER_PROVIDER_CONNECTION_UPDATED", "PROVIDER_CONNECTION",
                connectionId, null, safeSummary(updated), request);
        return updated;
    }

    @PostMapping("/connections/{connectionId}/test")
    public UserProviderConnectionService.TestResult test(
            @PathVariable UUID connectionId,
            @AuthenticationPrincipal AppUserPrincipal user,
            HttpServletRequest request) {
        var result = connections.test(user.id(), connectionId);
        audit.record(user.id(), "USER_PROVIDER_CONNECTION_TESTED", "PROVIDER_CONNECTION",
                connectionId, null,
                "{\"passed\":" + result.passed() + ",\"taskType\":\"" + result.taskType() + "\"}",
                request);
        return result;
    }

    @PutMapping("/connections/{connectionId}/status")
    public UserProviderConnectionService.UserConnectionView status(
            @PathVariable UUID connectionId,
            @RequestBody StatusRequest body,
            @AuthenticationPrincipal AppUserPrincipal user,
            HttpServletRequest request) {
        var updated = connections.setStatus(user.id(), connectionId, body.status());
        audit.record(user.id(), "USER_PROVIDER_CONNECTION_" + updated.status(),
                "PROVIDER_CONNECTION", connectionId, null, safeSummary(updated), request);
        return updated;
    }

    @DeleteMapping("/connections/{connectionId}")
    public void revoke(
            @PathVariable UUID connectionId,
            @AuthenticationPrincipal AppUserPrincipal user,
            HttpServletRequest request) {
        connections.revoke(user.id(), connectionId);
        audit.record(user.id(), "USER_PROVIDER_CONNECTION_REVOKED", "PROVIDER_CONNECTION",
                connectionId, null, null, request);
    }

    private String safeSummary(UserProviderConnectionService.UserConnectionView connection) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "taskType", connection.taskType(),
                    "vendor", connection.vendor(),
                    "status", connection.status(),
                    "fallback", connection.platformFallbackEnabled()));
        } catch (Exception error) {
            throw new IllegalStateException("无法记录模型连接审计", error);
        }
    }

    public record StatusRequest(String status) {}
}
