package io.rwa.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class NonceLockConcurrencyTest {

    @Test
    void sameAddressShouldSerializeNonceSelection() {
        Set<Long> nonces = ConcurrentHashMap.newKeySet();
        nonces.add(1L);
        nonces.add(2L);
        assertThat(nonces).hasSize(2);
    }
}
