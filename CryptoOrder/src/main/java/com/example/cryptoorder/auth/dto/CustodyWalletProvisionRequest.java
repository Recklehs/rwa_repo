package com.example.cryptoorder.auth.dto;

import java.util.UUID;

public record CustodyWalletProvisionRequest(
        UUID userId,
        String provider,
        String externalUserId
) {
}
