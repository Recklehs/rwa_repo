package io.rwa.server.tx;

import java.math.BigInteger;

public record GasPreflightEstimates(
    BigInteger nonce,
    BigInteger gasEstimate,
    BigInteger gasLimit,
    BigInteger baseFeePerGas,
    BigInteger maxFeePerGas,
    BigInteger priorityFee,
    BigInteger l1FeeWei
) {
}

