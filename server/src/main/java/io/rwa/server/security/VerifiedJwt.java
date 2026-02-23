package io.rwa.server.security;

import java.util.UUID;

public record VerifiedJwt(
    UUID userId,
    String provider,
    String externalUserId
) {
}
