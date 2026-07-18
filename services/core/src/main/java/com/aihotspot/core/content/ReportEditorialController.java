package com.aihotspot.core.content;

import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reports")
public class ReportEditorialController {
    private final ReportEditorialService service;
    public ReportEditorialController(ReportEditorialService service) { this.service = service; }
    @GetMapping @PreAuthorize("hasAuthority('report:read')")
    public List<ReportEditorialMapper.IssueView> list(@RequestParam(defaultValue = "50") int limit) { return service.list(limit); }
    @PostMapping("/generate") @PreAuthorize("hasAuthority('report:manage')")
    public ReportEditorialMapper.IssueView generate(@RequestParam(defaultValue = "DAILY") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchor,
            @AuthenticationPrincipal AppUserPrincipal actor, HttpServletRequest request) { return service.generate(period, anchor, actor, request); }
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('report:manage')")
    public ReportEditorialMapper.IssueView update(@PathVariable UUID id, @Valid @RequestBody EditRequest body,
            @AuthenticationPrincipal AppUserPrincipal actor, HttpServletRequest request) { return service.update(id, body.headline(), body.lead(), body.version(), actor, request); }
    @PostMapping("/{id}/publish") @PreAuthorize("hasAuthority('report:manage')")
    public ReportEditorialMapper.IssueView publish(@PathVariable UUID id, @Valid @RequestBody VersionRequest body,
            @AuthenticationPrincipal AppUserPrincipal actor, HttpServletRequest request) { return service.publish(id, body.version(), actor, request); }
    public record EditRequest(@NotBlank @Size(max=300) String headline, @NotBlank @Size(max=5000) String lead, long version) {}
    public record VersionRequest(long version) {}
}
