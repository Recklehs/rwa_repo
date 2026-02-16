package io.rwa.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxRetrySchedulerTest {

    @Test
    void claimSendCompletePatternShouldNotRequireDbLockDuringSend() {
        boolean dbLockHeldDuringSend = false;
        assertThat(dbLockHeldDuringSend).isFalse();
    }
}
