package com.aihotspot.core.crawl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ai-hotspot.crawl.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class CrawlScheduler {

    private final CrawlService service;

    public CrawlScheduler(CrawlService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${ai-hotspot.crawl.scheduler-fixed-delay:30000}")
    public void dispatchDue() {
        service.dispatchDue(20);
    }
}
