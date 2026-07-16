package com.aihotspot.core.config;

import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "aihot.events";
    public static final String DEAD_LETTER_EXCHANGE = "aihot.dlx";

    private static final List<String> WORKER_QUEUES = List.of(
            "q.crawl.worker",
            "q.content.worker",
            "q.embedding.worker",
            "q.cluster.worker",
            "q.report.worker",
            "q.notification.worker",
            "q.agent.worker");

    @Bean
    Declarables aiHotspotTopology() {
        TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
        DirectExchange deadLetters = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);

        List<Declarable> declarables = new java.util.ArrayList<>();
        declarables.add(events);
        declarables.add(deadLetters);
        for (String queueName : WORKER_QUEUES) {
            String deadLetterQueueName = queueName + ".dlq";
            Queue queue = QueueBuilder.durable(queueName)
                    .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                    .deadLetterRoutingKey(queueName)
                    .build();
            Queue deadLetterQueue = QueueBuilder.durable(deadLetterQueueName).build();
            Binding binding = BindingBuilder.bind(queue).to(events).with(routeFor(queueName));
            Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(queueName);
            declarables.add(queue);
            declarables.add(binding);
            declarables.add(deadLetterQueue);
            declarables.add(deadLetterBinding);
        }
        return new Declarables(declarables);
    }

    private String routeFor(String queueName) {
        return switch (queueName) {
            case "q.crawl.worker" -> "source.crawl.#";
            case "q.content.worker" -> "content.processing.#";
            case "q.embedding.worker" -> "embedding.#";
            case "q.cluster.worker" -> "event.cluster.#";
            case "q.report.worker" -> "report.#";
            case "q.notification.worker" -> "notification.#";
            case "q.agent.worker" -> "agent.run.#";
            default -> throw new IllegalArgumentException("Unsupported queue: " + queueName);
        };
    }
}
