package com.example.cryptoorder.auth.dto;

import java.util.UUID;

public record CustodyWalletProvisionResponse(
        UUID userId,
        String address,
        String complianceStatus
) {
}
