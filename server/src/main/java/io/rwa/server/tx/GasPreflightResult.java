package io.rwa.server.tx;

import java.math.BigInteger;

public record GasPreflightResult(
    BigInteger requiredUpperWei,
    BigInteger currentBalanceWei,
    boolean toppedUp,
    String topUpTxHash,
    GasPreflightEstimates estimates
) {
}

