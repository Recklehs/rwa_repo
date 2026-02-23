package io.rwa.server.security;

import com.nimbusds.jwt.JWTClaimsSet;
import io.rwa.server.common.ApiException;
import io.rwa.server.config.RwaProperties;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class JwtClaimValidator {

    private JwtClaimValidator() {
    }

    static VerifiedJwt validate(JWTClaimsSet claims, RwaProperties properties) {
        if (claims == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid JWT claims");
        }

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT sub is required");
        }

        UUID userId;
        try {
            userId = UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT sub must be UUID");
        }

        String expectedIssuer = SecurityPropertyResolver.jwtIssuer(properties);
        if (!expectedIssuer.equals(claims.getIssuer())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT issuer mismatch");
        }

        String expectedAudience = SecurityPropertyResolver.jwtAudience(properties);
        List<String> audience = claims.getAudience();
        if (audience == null || audience.stream().noneMatch(expectedAudience::equals)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT audience mismatch");
        }

        long skewSeconds = SecurityPropertyResolver.clockSkewSeconds(properties);
        Instant now = Instant.now();

        Date issuedAt = claims.getIssueTime();
        Date expiresAt = claims.getExpirationTime();

        if (issuedAt == null || expiresAt == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT iat/exp is required");
        }

        Instant iat = issuedAt.toInstant();
        Instant exp = expiresAt.toInstant();

        if (iat.minusSeconds(skewSeconds).isAfter(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT issued-at is in the future");
        }
        if (exp.plusSeconds(skewSeconds).isBefore(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT is expired");
        }

        Date notBefore = claims.getNotBeforeTime();
        if (notBefore != null && notBefore.toInstant().minusSeconds(skewSeconds).isAfter(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT not active yet");
        }

        String provider = toNullableString(claims.getClaim("provider"));
        String externalUserId = toNullableString(claims.getClaim("externalUserId"));

        return new VerifiedJwt(userId, provider, externalUserId);
    }

    private static String toNullableString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
