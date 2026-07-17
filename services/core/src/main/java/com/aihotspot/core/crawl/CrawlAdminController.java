package com.aihotspot.core.crawl;

import com.aihotspot.core.auth.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class CrawlAdminController {

    private final CrawlService service;

    public CrawlAdminController(CrawlService service) {
        this.service = service;
    }

    @PostMapping("/fetch-jobs/dispatch-due")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('fetch:manage')")
    public DispatchResponse dispatchDue(@RequestParam(defaultValue = "20") int limit) {
        List<CrawlMapper.FetchJobView> items = service.dispatchDue(limit);
        return new DispatchResponse(items.size(), items);
    }

    @PostMapping("/fetch-jobs/endpoints/{endpointId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('fetch:manage')")
    public CrawlMapper.FetchJobView trigger(
            @PathVariable UUID endpointId,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request) {
        return service.trigger(endpointId, principal, request);
    }

    @GetMapping("/fetch-jobs")
    @PreAuthorize("hasAuthority('fetch:manage')")
    public List<CrawlMapper.FetchJobView> jobs(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return service.listJobs(status, limit);
    }

    @GetMapping("/fetch-jobs/{id}")
    @PreAuthorize("hasAuthority('fetch:manage')")
    public CrawlMapper.FetchJobView job(@PathVariable UUID id) {
        return service.getJob(id);
    }

    @GetMapping("/dead-letters")
    @PreAuthorize("hasAuthority('dead-letter:manage')")
    public List<CrawlMapper.DeadLetterView> deadLetters(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return service.listDeadLetters(status, limit);
    }

    @PostMapping("/dead-letters/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('dead-letter:manage')")
    public CrawlMapper.FetchJobView replay(
            @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request) {
        return service.replayDeadLetter(id, principal, request);
    }

    public record DispatchResponse(int dispatched, List<CrawlMapper.FetchJobView> items) {}
}
