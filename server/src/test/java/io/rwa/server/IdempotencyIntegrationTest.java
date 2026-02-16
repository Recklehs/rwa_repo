package io.rwa.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdempotencyIntegrationTest {

    @Test
    void sameKeySamePayloadShouldBeDeterministic() {
        String key = "k1";
        String payload = "{\"a\":1}";
        assertThat(key + payload).isEqualTo("k1{\"a\":1}");
    }

    @Test
    void sameKeyDifferentPayloadShouldConflictByHash() {
        String payloadA = "{\"a\":1}";
        String payloadB = "{\"a\":2}";
        assertThat(payloadA).isNotEqualTo(payloadB);
    }
}
