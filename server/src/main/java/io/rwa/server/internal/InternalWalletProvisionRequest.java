package io.rwa.server.internal;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InternalWalletProvisionRequest(
    @NotNull UUID userId,
    String provider,
    String externalUserId
) {
}
