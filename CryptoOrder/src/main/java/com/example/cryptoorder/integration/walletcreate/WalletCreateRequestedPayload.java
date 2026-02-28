package com.example.cryptoorder.integration.walletcreate;

import java.util.UUID;

public record WalletCreateRequestedPayload(
        UUID userId,
        String provider,
        String externalUserId
) {
}
