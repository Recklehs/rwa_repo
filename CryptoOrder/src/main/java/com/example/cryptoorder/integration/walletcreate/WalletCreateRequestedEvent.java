package com.example.cryptoorder.integration.walletcreate;

import java.time.Instant;
import java.util.UUID;

public record WalletCreateRequestedEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String source,
        String correlationId,
        WalletCreateRequestedPayload payload
) {
}
