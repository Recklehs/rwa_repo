package io.rwa.server.integration;

import java.util.UUID;

public record WalletCreateRequestedPayload(
    UUID userId,
    String provider,
    String externalUserId
) {
}
