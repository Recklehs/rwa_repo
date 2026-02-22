package io.rwa.ingester.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IngesterConfigTest {

    @Test
    void fromEnv_setsDlqTopicDefault() {
        IngesterConfig config = IngesterConfig.fromEnv(Map.of());

        assertEquals("chain.logs.dlq", config.kafkaDlqTopic());
    }

    @Test
    void fromEnv_overridesDlqTopic() {
        IngesterConfig config = IngesterConfig.fromEnv(
            Map.of(
                "KAFKA_DLQ_TOPIC", "custom.dlq.topic"
            )
        );

        assertEquals("custom.dlq.topic", config.kafkaDlqTopic());
    }
}
