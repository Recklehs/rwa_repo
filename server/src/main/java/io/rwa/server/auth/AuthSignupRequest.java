package io.rwa.server.auth;

import jakarta.validation.constraints.NotBlank;

public record AuthSignupRequest(
    @NotBlank String externalUserId,
    String provider
) {
}
