package io.rwa.server.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.server.config.RwaProperties;
import java.sql.Timestamp;
import java.sql.Types;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private static final Logger log = LoggerFactory.getLogger(OutboxRepository.class);
    private static final String EVENT_INSERT_SQL_JSONB = """
        INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, event_type, payload, topic, partition_key, occurred_at)
        VALUES (:eventId, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb), :topic, :partitionKey, :occurredAt)
        """;
    private static final String EVENT_INSERT_SQL_TEXT = """
        INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, event_type, payload, topic, partition_key, occurred_at)
        VALUES (:eventId, :aggregateType, :aggregateId, :eventType, :payload, :topic, :partitionKey, :occurredAt)
        """;
    private static final String OUTBOX_PAYLOAD_TYPE_SQL_CURRENT_SCHEMA = """
        SELECT data_type
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'outbox_event'
          AND column_name = 'payload'
        """;
    private static final String OUTBOX_PAYLOAD_TYPE_SQL_PUBLIC = """
        SELECT data_type
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'outbox_event'
          AND column_name = 'payload'
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RwaProperties properties;
    private volatile Boolean outboxPayloadJsonb;

    public OutboxRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper, RwaProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void insertEventAndInitDelivery(DomainEvent event) {
        String eventSql = isOutboxPayloadJsonb() ? EVENT_INSERT_SQL_JSONB : EVENT_INSERT_SQL_TEXT;
        String deliverySql = """
            INSERT INTO outbox_delivery(event_id, status, attempt_count, updated_at)
            VALUES (:eventId, :status, 0, now())
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("eventId", event.eventId())
            .addValue("aggregateType", event.aggregateType())
            .addValue("aggregateId", event.aggregateId())
            .addValue("eventType", event.eventType())
            .addValue("payload", event.payload().toString())
            .addValue("topic", event.topic())
            .addValue("partitionKey", event.partitionKey())
            .addValue("occurredAt", Timestamp.from(event.occurredAt()), Types.TIMESTAMP);

        jdbcTemplate.update(eventSql, params);
        jdbcTemplate.update(deliverySql, new MapSqlParameterSource()
            .addValue("eventId", event.eventId())
            .addValue("status", OutboxDeliveryStatus.INIT.name()));
    }

    public Optional<OutboxEventRow> findEvent(UUID eventId) {
        String sql = """
            SELECT event_id, topic, partition_key, payload, created_at
            FROM outbox_event
            WHERE event_id = :eventId
            """;
        List<OutboxEventRow> rows = jdbcTemplate.query(sql, Map.of("eventId", eventId), this::toOutboxEventRow);
        return rows.stream().findFirst();
    }

    public void markSendSuccess(UUID eventId) {
        String sql = """
            UPDATE outbox_delivery
            SET status = :status,
                sent_at = now(),
                locked_by = null,
                locked_at = null,
                updated_at = now()
            WHERE event_id = :eventId
            """;
        jdbcTemplate.update(sql, Map.of(
            "eventId", eventId,
            "status", OutboxDeliveryStatus.SEND_SUCCESS.name()
        ));
    }

    public void markSendFail(UUID eventId, String error, Duration backoff) {
        String sql = """
            UPDATE outbox_delivery
            SET status = :status,
                attempt_count = attempt_count + 1,
                last_error = :lastError,
                next_retry_at = now() + CAST(:backoff AS interval),
                locked_by = null,
                locked_at = null,
                updated_at = now()
            WHERE event_id = :eventId
            """;
        String interval = backoff.toSeconds() + " seconds";
        jdbcTemplate.update(sql, new MapSqlParameterSource()
            .addValue("eventId", eventId)
            .addValue("status", OutboxDeliveryStatus.SEND_FAIL.name())
            .addValue("lastError", error)
            .addValue("backoff", interval));
    }

    public List<UUID> claimBatch(String instanceId) {
        int retryMinAgeMinutes = properties.getOutbox().getRetryMinAgeMinutes();
        int maxAttempts = properties.getOutbox().getMaxAttempts();
        int lockTtl = properties.getOutbox().getLockTtlSeconds();
        int batchSize = properties.getOutbox().getClaimBatchSize();

        String candidateSql = """
            SELECT d.event_id
            FROM outbox_delivery d
            JOIN outbox_event e ON e.event_id = d.event_id
            WHERE d.status <> 'SEND_SUCCESS'
              AND e.created_at <= now() - CAST(:retryMinAge AS interval)
              AND d.attempt_count < :maxAttempts
              AND (d.next_retry_at IS NULL OR d.next_retry_at <= now())
              AND (d.locked_at IS NULL OR d.locked_at <= now() - CAST(:lockTtl AS interval))
            ORDER BY e.created_at ASC
            LIMIT :batchSize
            """;

        String retryAge = retryMinAgeMinutes + " minutes";
        String lockTtlInterval = lockTtl + " seconds";

        List<UUID> candidates = jdbcTemplate.queryForList(candidateSql, new MapSqlParameterSource()
            .addValue("retryMinAge", retryAge)
            .addValue("maxAttempts", maxAttempts)
            .addValue("lockTtl", lockTtlInterval)
            .addValue("batchSize", batchSize), UUID.class);

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<UUID> claimed = new ArrayList<>();
        String claimSql = """
            UPDATE outbox_delivery
            SET locked_by = :lockedBy,
                locked_at = now(),
                updated_at = now()
            WHERE event_id = :eventId
              AND (locked_at IS NULL OR locked_at <= now() - CAST(:lockTtl AS interval))
            """;

        for (UUID eventId : candidates) {
            int updated = jdbcTemplate.update(claimSql, new MapSqlParameterSource()
                .addValue("lockedBy", instanceId)
                .addValue("eventId", eventId)
                .addValue("lockTtl", lockTtlInterval));
            if (updated == 1) {
                claimed.add(eventId);
            }
        }

        return claimed;
    }

    public void unlock(UUID eventId) {
        String sql = """
            UPDATE outbox_delivery
            SET locked_by = null,
                locked_at = null,
                updated_at = now()
            WHERE event_id = :eventId
            """;
        jdbcTemplate.update(sql, Map.of("eventId", eventId));
    }

    private boolean isOutboxPayloadJsonb() {
        Boolean cached = outboxPayloadJsonb;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (outboxPayloadJsonb != null) {
                return outboxPayloadJsonb;
            }

            String dataType = findOutboxPayloadDataType();
            boolean detectedJsonb = dataType == null || "jsonb".equalsIgnoreCase(dataType);
            outboxPayloadJsonb = detectedJsonb;
            return detectedJsonb;
        }
    }

    private String findOutboxPayloadDataType() {
        try {
            return jdbcTemplate.queryForObject(OUTBOX_PAYLOAD_TYPE_SQL_CURRENT_SCHEMA, Map.of(), String.class);
        } catch (DataAccessException currentSchemaFailure) {
            try {
                return jdbcTemplate.queryForObject(OUTBOX_PAYLOAD_TYPE_SQL_PUBLIC, Map.of(), String.class);
            } catch (DataAccessException publicSchemaFailure) {
                log.warn(
                    "Failed to inspect outbox_event.payload type. defaulting to jsonb binding. currentSchemaErr={}, publicSchemaErr={}",
                    currentSchemaFailure.getMessage(),
                    publicSchemaFailure.getMessage()
                );
                return null;
            }
        }
    }

    private OutboxEventRow toOutboxEventRow(ResultSet rs, int rowNum) throws SQLException {
        String payloadText = rs.getString("payload");
        JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadText);
        } catch (Exception e) {
            payload = objectMapper.createObjectNode().put("raw", payloadText);
        }
        return new OutboxEventRow(
            rs.getObject("event_id", UUID.class),
            rs.getString("topic"),
            rs.getString("partition_key"),
            payload,
            rs.getObject("created_at", java.time.Instant.class)
        );
    }
}
