package io.rwa.server.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.rwa.server.common.ApiException;
import io.rwa.server.config.RwaProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LocalJwtIssuer {

    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final RwaProperties properties;

    public LocalJwtIssuer(RwaProperties properties) {
        this.properties = properties;
    }

    public IssuedToken issue(UUID userId, String provider, String externalUserId) {
        byte[] secret = decodeSecret();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(DEFAULT_TTL);

        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
            .subject(userId.toString())
            .issuer(SecurityPropertyResolver.jwtIssuer(properties))
            .audience(SecurityPropertyResolver.jwtAudience(properties))
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiresAt));

        if (provider != null && !provider.isBlank()) {
            builder.claim("provider", provider);
        }
        if (externalUserId != null && !externalUserId.isBlank()) {
            builder.claim("externalUserId", externalUserId);
        }

        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), builder.build());
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to sign local JWT");
        }

        return new IssuedToken(jwt.serialize(), DEFAULT_TTL.toSeconds());
    }

    private byte[] decodeSecret() {
        String base64 = SecurityPropertyResolver.hmacSecretBase64(properties);
        if (base64 == null || base64.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_HMAC_SECRET_BASE64 is required for LOCAL auth");
        }
        try {
            byte[] secret = Base64.getDecoder().decode(base64);
            if (secret.length < 32) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_HMAC_SECRET_BASE64 must decode to at least 32 bytes");
            }
            return secret;
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_HMAC_SECRET_BASE64 is invalid base64");
        }
    }

    public record IssuedToken(String accessToken, long expiresInSeconds) {
    }
}
