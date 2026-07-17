package com.aihotspot.core.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audits")
@PreAuthorize("hasAuthority('audit:read')")
public class AuditAdminController {

    private final AuditService service;

    public AuditAdminController(AuditService service) { this.service = service; }

    @GetMapping
    public List<AuditMapper.AuditView> list(
            @RequestParam String targetType,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(defaultValue = "50") int limit) {
        return service.findByTarget(targetType, targetId, limit);
    }
}
