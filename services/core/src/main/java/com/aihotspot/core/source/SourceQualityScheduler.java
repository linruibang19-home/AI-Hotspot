package com.aihotspot.core.source;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ai-hotspot.source-quality.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class SourceQualityScheduler {

    private final SourceQualityService service;

    public SourceQualityScheduler(SourceQualityService service) {
        this.service = service;
    }

    @Scheduled(cron = "${ai-hotspot.source-quality.scheduler-cron:0 15 2 * * MON}", zone = "Asia/Shanghai")
    public void captureWeekly() {
        service.captureWeeklySnapshot();
    }
}
