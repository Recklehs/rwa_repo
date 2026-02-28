package com.example.cryptoorder.auth.security;

import com.example.cryptoorder.auth.config.AuthServerProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenService {

    private final AuthServerProperties authServerProperties;
    private final JwtKeyProvider jwtKeyProvider;

    public String issueAccessToken(UUID userId, String provider, String externalUserId) {
        try {
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(authServerProperties.getAccessTokenTtlSeconds());

            JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .issuer(authServerProperties.getJwtIssuer())
                    .audience(authServerProperties.getJwtAudience())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiresAt))
                    .claim("roles", List.of("USER"))
                    .jwtID(UUID.randomUUID().toString());

            if (provider != null && !provider.isBlank()) {
                claimsBuilder.claim("provider", provider);
            }
            if (externalUserId != null && !externalUserId.isBlank()) {
                claimsBuilder.claim("externalUserId", externalUserId);
            }

            SignedJWT jwt = new SignedJWT(buildHeader(), claimsBuilder.build());

            if (authServerProperties.getMode() == AuthServerProperties.Mode.JWKS) {
                jwt.sign(new RSASSASigner(jwtKeyProvider.getRsaPrivateKey()));
            } else {
                jwt.sign(new MACSigner(jwtKeyProvider.getHmacSecret()));
            }

            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Access Token 발급에 실패했습니다.", e);
        }
    }

    private JWSHeader buildHeader() {
        if (authServerProperties.getMode() == AuthServerProperties.Mode.JWKS) {
            return new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(authServerProperties.getJwtKeyId())
                    .type(JOSEObjectType.JWT)
                    .build();
        }

        return new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT)
                .build();
    }
}
