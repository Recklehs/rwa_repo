package io.rwa.server.trade;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigInteger;
import java.util.UUID;

public record TradeBuyRequest(
    UUID buyerUserId,
    @NotNull @Positive BigInteger listingId,
    @NotNull @Positive BigInteger amount
) {
}
