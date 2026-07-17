package com.aihotspot.core.source;

import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/sources")
public class SourceAdminController {

    private final SourceService service;

    public SourceAdminController(SourceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('source:read')")
    public SourceListResponse list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "50") int limit) {
        return new SourceListResponse(service.metrics(), service.list(status, query, limit));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('source:read')")
    public SourceService.SourceView detail(@PathVariable UUID id) { return service.get(id); }

    @GetMapping("/connector-schemas")
    @PreAuthorize("hasAuthority('source:read')")
    public Map<String, Object> connectorSchemas() { return service.connectorSchemas(); }

    @PostMapping
    @PreAuthorize("hasAuthority('source:write')")
    public SourceService.CreatedSource create(
            @Valid @RequestBody CreateSourceRequest body,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request) {
        return service.create(body.toCommand(), principal, request);
    }

    @PostMapping("/endpoints/{id}/probe")
    @PreAuthorize("hasAuthority('source:probe')")
    public SourceService.ProbeResult probe(
            @PathVariable UUID id,
            @Valid @RequestBody VersionRequest body,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request) {
        return service.probe(id, body.version(), principal, request);
    }

    @PostMapping("/endpoints/{id}/activate")
    @PreAuthorize("hasAuthority('source:activate')")
    public SourceMapper.EndpointDetail activate(
            @PathVariable UUID id,
            @Valid @RequestBody VersionRequest body,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request) {
        return service.changeStatus(id, "ACTIVE", body.version(), principal, request);
    }

    @PostMapping("/endpoints/{id}/pause")
    @PreAuthorize("hasAuthority('source:activate')")
    public SourceMapper.EndpointDetail pause(
            @PathVariable UUID id,
            @Valid @RequestBody VersionRequest body,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request) {
        return service.changeStatus(id, "PAUSED", body.version(), principal, request);
    }

    public record SourceListResponse(SourceMapper.SourceMetrics metrics, List<SourceMapper.SourceSummary> items) {}
    public record VersionRequest(@Min(0) long version) {}
    public record CreateSourceRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
            @NotBlank String entityType,
            @Size(max = 2) String countryCode,
            @NotBlank String officialLevel,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal authorityScore,
            String websiteUrl,
            String logoUrl,
            @NotBlank @Size(max = 120) String endpointName,
            @NotBlank String endpointUrl,
            @NotBlank String endpointType,
            String language,
            @Min(300) @Max(2592000) int pollingIntervalSeconds,
            @NotBlank String displayPolicy,
            @NotBlank String indexPolicy,
            @DecimalMin("0") @DecimalMax("100") BigDecimal authorityOverride,
            Map<String, Object> config) {
        SourceService.CreateSource toCommand() {
            return new SourceService.CreateSource(name, slug, entityType, countryCode, officialLevel, authorityScore,
                    websiteUrl, logoUrl, endpointName, endpointUrl, endpointType, language,
                    pollingIntervalSeconds, displayPolicy, indexPolicy, authorityOverride,
                    config == null ? Map.of() : config);
        }
    }
}
