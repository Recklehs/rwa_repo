package io.rwa.server.trade;

import java.math.BigInteger;
import java.util.UUID;

public record TradeCancelResult(
    UUID outboxId,
    BigInteger listingId
) {
}
