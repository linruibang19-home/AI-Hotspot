package com.aihotspot.core.content;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/reports")
public class PublicReportController {

    private final PublicReportService service;

    public PublicReportController(PublicReportService service) {
        this.service = service;
    }

    @GetMapping
    public PublicReportService.ReportResponse get(
            @RequestParam(defaultValue = "DAILY") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchor) {
        return service.get(period, anchor);
    }
}
