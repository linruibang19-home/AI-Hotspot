package com.aihotspot.core.messaging;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxStore {

    public record OutboxEvent(UUID id, UUID eventId, String eventType, String payloadJson) {}

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OutboxStore(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(
            UUID eventId,
            String eventType,
            int eventVersion,
            String aggregateType,
            UUID aggregateId,
            String payloadJson) {
        String sql = """
                insert into messaging.outbox_event (
                    id, event_id, event_type, event_version, aggregate_type,
                    aggregate_id, payload, status, retry_count, created_at
                ) values (
                    :id, :eventId, :eventType, :eventVersion, :aggregateType,
                    :aggregateId, cast(:payload as jsonb), 'PENDING', 0, :createdAt
                )
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource(Map.of(
                "id", UUID.randomUUID(),
                "eventId", eventId,
                "eventType", eventType,
                "eventVersion", eventVersion,
                "aggregateType", aggregateType,
                "aggregateId", aggregateId,
                "payload", payloadJson,
                "createdAt", OffsetDateTime.now()));
        parameters.registerSqlType("payload", Types.VARCHAR);
        jdbcTemplate.update(sql, parameters);
    }

    @Transactional
    public List<OutboxEvent> claimBatch(int batchSize) {
        String selectSql = """
                select id, event_id, event_type, payload::text as payload_json
                from messaging.outbox_event
                where status in ('PENDING', 'FAILED')
                  and (next_retry_at is null or next_retry_at <= now())
                order by created_at
                for update skip locked
                limit :batchSize
                """;
        List<OutboxEvent> events = jdbcTemplate.query(
                selectSql,
                Map.of("batchSize", batchSize),
                (resultSet, rowNumber) -> new OutboxEvent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload_json")));
        if (!events.isEmpty()) {
            jdbcTemplate.update(
                    "update messaging.outbox_event set status = 'PUBLISHING' where id in (:ids)",
                    Map.of("ids", events.stream().map(OutboxEvent::id).toList()));
        }
        return events;
    }

    public void markPublished(UUID id) {
        jdbcTemplate.update(
                """
                update messaging.outbox_event
                set status = 'PUBLISHED', published_at = now(), last_error = null
                where id = :id
                """,
                Map.of("id", id));
    }

    public void markFailed(UUID id, String error) {
        jdbcTemplate.update(
                """
                update messaging.outbox_event
                set status = 'FAILED', retry_count = retry_count + 1,
                    next_retry_at = now() + interval '1 minute', last_error = :error
                where id = :id
                """,
                Map.of("id", id, "error", error.substring(0, Math.min(error.length(), 2000))));
    }
}
