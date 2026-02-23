package io.rwa.server.wallet;

import java.util.UUID;

public record WalletTransferResult(
    UUID outboxId,
    String txType,
    String txStatus,
    String txHash
) {
}
