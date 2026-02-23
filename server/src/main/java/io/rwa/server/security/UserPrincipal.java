package io.rwa.server.security;

import java.util.UUID;

public record UserPrincipal(
    UUID userId,
    String provider,
    String externalUserId
) {
}
