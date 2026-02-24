package io.rwa.server.integration;

import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationInboxService {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_PROCESSED = "PROCESSED";
    private static final String STATUS_FAILED = "FAILED";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public IntegrationInboxService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean claimForProcessing(UUID eventId, String eventType, String payloadJson) {
        int inserted = jdbcTemplate.update(
            """
                INSERT INTO integration_inbox_events(event_id, event_type, payload, status, created_at, updated_at)
                VALUES (:eventId, :eventType, CAST(:payload AS jsonb), :status, now(), now())
                ON CONFLICT (event_id) DO NOTHING
                """,
            new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("eventType", eventType)
                .addValue("payload", payloadJson)
                .addValue("status", STATUS_PROCESSING)
        );

        if (inserted == 1) {
            return true;
        }

        String existingStatus = jdbcTemplate.query(
            """
                SELECT status
                FROM integration_inbox_events
                WHERE event_id = :eventId
                """,
            Map.of("eventId", eventId),
            rs -> rs.next() ? rs.getString("status") : null
        );

        if (STATUS_PROCESSED.equals(existingStatus)) {
            return false;
        }

        jdbcTemplate.update(
            """
                UPDATE integration_inbox_events
                SET status = :status,
                    last_error = null,
                    updated_at = now()
                WHERE event_id = :eventId
                """,
            new MapSqlParameterSource()
                .addValue("status", STATUS_PROCESSING)
                .addValue("eventId", eventId)
        );

        return true;
    }

    @Transactional
    public void markProcessed(UUID eventId) {
        jdbcTemplate.update(
            """
                UPDATE integration_inbox_events
                SET status = :status,
                    last_error = null,
                    processed_at = now(),
                    updated_at = now()
                WHERE event_id = :eventId
                """,
            new MapSqlParameterSource()
                .addValue("status", STATUS_PROCESSED)
                .addValue("eventId", eventId)
        );
    }

    @Transactional
    public void markFailed(UUID eventId, String error) {
        jdbcTemplate.update(
            """
                UPDATE integration_inbox_events
                SET status = :status,
                    last_error = :error,
                    updated_at = now()
                WHERE event_id = :eventId
                """,
            new MapSqlParameterSource()
                .addValue("status", STATUS_FAILED)
                .addValue("error", error == null ? "unknown" : error, Types.VARCHAR)
                .addValue("eventId", eventId)
        );
    }
}
