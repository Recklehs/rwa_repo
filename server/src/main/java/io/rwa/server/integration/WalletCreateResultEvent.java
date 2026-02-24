package io.rwa.server.integration;

import java.time.Instant;
import java.util.UUID;

public record WalletCreateResultEvent(
    UUID eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String source,
    String correlationId,
    UUID requestEventId,
    WalletCreateResultPayload payload
) {
    public record WalletCreateResultPayload(
        UUID userId,
        String address,
        String complianceStatus,
        String error
    ) {
    }
}
