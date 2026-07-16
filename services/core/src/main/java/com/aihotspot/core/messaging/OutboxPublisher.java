package com.aihotspot.core.messaging;

import com.aihotspot.core.config.RabbitTopologyConfig;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ai-hotspot.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private final OutboxStore outboxStore;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;

    public OutboxPublisher(
            OutboxStore outboxStore,
            RabbitTemplate rabbitTemplate,
            @Value("${ai-hotspot.outbox.batch-size}") int batchSize) {
        this.outboxStore = outboxStore;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ai-hotspot.outbox.fixed-delay}")
    public void publishPending() {
        for (OutboxStore.OutboxEvent event : outboxStore.claimBatch(batchSize)) {
            publish(event);
        }
    }

    private void publish(OutboxStore.OutboxEvent event) {
        try {
            Message message = MessageBuilder.withBody(event.payloadJson().getBytes(StandardCharsets.UTF_8))
                    .setContentType("application/json")
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(event.eventId().toString())
                    .build();
            CorrelationData correlationData = new CorrelationData(event.eventId().toString());
            rabbitTemplate.send(
                    RabbitTopologyConfig.EVENTS_EXCHANGE, event.eventType(), message, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("RabbitMQ publisher confirm NACK: " + confirm.reason());
            }
            outboxStore.markPublished(event.id());
        } catch (Exception exception) {
            outboxStore.markFailed(event.id(), exception.getMessage() == null ? exception.toString() : exception.getMessage());
        }
    }
}
