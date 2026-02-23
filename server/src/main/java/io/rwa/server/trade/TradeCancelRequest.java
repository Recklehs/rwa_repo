package io.rwa.server.trade;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigInteger;

public record TradeCancelRequest(
    @NotNull @Positive BigInteger listingId
) {
}
