package com.aihotspot.core.api;

import com.aihotspot.core.messaging.OutboxStore;
import com.aihotspot.core.notification.MailProvider;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/smoke")
@ConditionalOnProperty(name = "ai-hotspot.smoke.enabled", havingValue = "true")
public class SystemSmokeController {

    private final OutboxStore outboxStore;
    private final MailProvider mailProvider;

    public SystemSmokeController(OutboxStore outboxStore, MailProvider mailProvider) {
        this.outboxStore = outboxStore;
        this.mailProvider = mailProvider;
    }

    @PostMapping("/outbox")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public Map<String, Object> outbox() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        String payload = """
                {"eventId":"%s","eventType":"content.processing.smoke","eventVersion":1,
                "aggregateType":"M1Smoke","aggregateId":"%s","idempotencyKey":"m1:%s",
                "correlationId":"%s","traceId":"%s","occurredAt":"%s","producer":"core-api",
                "payload":{"stage":"M1"}}
                """.formatted(eventId, aggregateId, eventId, correlationId, traceId, Instant.now()).replace("\n", "");
        outboxStore.append(
                eventId,
                "content.processing.smoke",
                1,
                "M1Smoke",
                aggregateId,
                payload);
        return Map.of("status", "ACCEPTED", "eventId", eventId);
    }

    @PostMapping("/mail")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> mail(@RequestBody SmokeMailRequest request) {
        mailProvider.send(
                request.recipient(),
                request.subject(),
                "<h1>AI Hotspot M1</h1><p>MailProvider 到 Mailpit 的基础链路已通过。</p>");
        return Map.of("status", "ACCEPTED", "provider", mailProvider.providerName());
    }

    public record SmokeMailRequest(String recipient, String subject) {}
}
