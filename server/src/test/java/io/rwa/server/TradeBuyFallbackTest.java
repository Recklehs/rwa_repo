package io.rwa.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class TradeBuyFallbackTest {

    @Test
    void costCalculationShouldMatchContractRoundingFloor() {
        BigInteger amount = new BigInteger("3");
        BigInteger unitPrice = new BigInteger("1000000000000000000");
        BigInteger scale = new BigInteger("1000000000000000000");
        BigInteger cost = amount.multiply(unitPrice).divide(scale);
        assertThat(cost).isEqualTo(new BigInteger("3"));
    }
}
