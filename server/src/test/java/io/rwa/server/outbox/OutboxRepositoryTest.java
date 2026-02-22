package io.rwa.server.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.server.config.RwaProperties;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class OutboxRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private OutboxRepository outboxRepository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outboxRepository = new OutboxRepository(jdbcTemplate, objectMapper, new RwaProperties());
    }

    @Test
    @DisplayName("outbox_event.payload가 jsonb면 CAST(:payload AS jsonb) SQL로 INSERT한다")
    void insertShouldUseJsonbCastWhenPayloadColumnIsJsonb() {
        when(jdbcTemplate.queryForObject(contains("table_schema = current_schema()"), anyMap(), eq(String.class)))
            .thenReturn("jsonb");
        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(1);

        DomainEvent event = sampleEvent();
        outboxRepository.insertEventAndInitDelivery(event);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));

        String eventSql = sqlCaptor.getAllValues().stream()
            .filter(sql -> sql.contains("INSERT INTO outbox_event"))
            .findFirst()
            .orElseThrow();
        assertThat(eventSql).contains("CAST(:payload AS jsonb)");

        String deliverySql = sqlCaptor.getAllValues().stream()
            .filter(sql -> sql.contains("INSERT INTO outbox_delivery"))
            .findFirst()
            .orElseThrow();
        assertThat(deliverySql).contains("INSERT INTO outbox_delivery");
    }

    @Test
    @DisplayName("outbox_event.payload가 text면 CAST 없이 plain payload 바인딩으로 INSERT한다")
    void insertShouldUsePlainPayloadWhenPayloadColumnIsText() {
        when(jdbcTemplate.queryForObject(contains("table_schema = current_schema()"), anyMap(), eq(String.class)))
            .thenReturn("text");
        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(1);

        DomainEvent event = sampleEvent();
        outboxRepository.insertEventAndInitDelivery(event);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));

        String eventSql = sqlCaptor.getAllValues().stream()
            .filter(sql -> sql.contains("INSERT INTO outbox_event"))
            .findFirst()
            .orElseThrow();
        assertThat(eventSql).doesNotContain("CAST(:payload AS jsonb)");
        assertThat(eventSql).contains("VALUES (:eventId, :aggregateType, :aggregateId, :eventType, :payload");
    }

    private DomainEvent sampleEvent() {
        return new DomainEvent(
            UUID.fromString("0199587d-78f9-7000-8000-000000000201"),
            "User",
            "0199587d-78f9-7000-8000-000000000201",
            "UserSignedUp",
            Instant.parse("2026-02-22T07:50:30Z"),
            "server.domain.events",
            "0199587d-78f9-7000-8000-000000000201",
            objectMapper.createObjectNode().put("userId", "0199587d-78f9-7000-8000-000000000201")
        );
    }
}
