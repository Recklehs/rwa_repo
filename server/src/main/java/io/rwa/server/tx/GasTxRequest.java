package io.rwa.server.tx;

import java.math.BigInteger;

public record GasTxRequest(
    String toAddress,
    String data,
    BigInteger value
) {
}

