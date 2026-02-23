package io.rwa.server.trade;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigInteger;
import java.util.UUID;

public record TradeListRequest(
    UUID sellerUserId,
    String tokenId,
    String unitId,
    @NotNull @Positive BigInteger amount,
    @NotNull @Positive BigInteger unitPrice
) {
}
