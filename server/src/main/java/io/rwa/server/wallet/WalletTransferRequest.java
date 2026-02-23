package io.rwa.server.wallet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

public record WalletTransferRequest(
    UUID recipientUserId,
    String recipientProvider,
    String recipientExternalUserId,
    BigInteger amount,
    BigDecimal amountHuman
) {
}
