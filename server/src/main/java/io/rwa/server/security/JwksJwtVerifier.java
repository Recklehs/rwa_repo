package io.rwa.server.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.rwa.server.common.ApiException;
import io.rwa.server.config.RwaProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class JwksJwtVerifier implements JwtVerifier {

    private static final List<JWSAlgorithm> SUPPORTED_ALGORITHMS = List.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);

    private final RwaProperties properties;
    private final HttpClient httpClient;

    private volatile CachedJwks cachedJwks;

    public JwksJwtVerifier(RwaProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    }

    @Override
    public VerifiedJwt verify(String token) {
        SignedJWT jwt = parseToken(token);
        JWSAlgorithm algorithm = jwt.getHeader().getAlgorithm();
        if (!SUPPORTED_ALGORITHMS.contains(algorithm)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unsupported JWT algorithm");
        }

        JWKSet jwkSet = loadJwks(false);
        if (!verifySignature(jwt, jwkSet, algorithm)) {
            // Key rotation can make a still-cached JWKS stale; force a single refresh before failing auth.
            try {
                JWKSet refreshed = loadJwks(true);
                if (verifySignature(jwt, refreshed, algorithm)) {
                    return JwtClaimValidator.validate(jwt.getJWTClaimsSet(), properties);
                }
            } catch (ApiException ignored) {
                // Ignore refresh fetch errors and return regular auth failure below.
            } catch (Exception ignored) {
                // Ignore parse/runtime refresh errors and return regular auth failure below.
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT signature mismatch");
        }

        try {
            return JwtClaimValidator.validate(jwt.getJWTClaimsSet(), properties);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid JWT claims");
        }
    }

    private SignedJWT parseToken(String token) {
        try {
            return SignedJWT.parse(token);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Malformed JWT");
        }
    }

    private JWKSet loadJwks(boolean forceRefresh) {
        Instant now = Instant.now();
        CachedJwks snapshot = cachedJwks;
        if (!forceRefresh && snapshot != null && now.isBefore(snapshot.expiresAt())) {
            return snapshot.jwkSet();
        }

        synchronized (this) {
            snapshot = cachedJwks;
            now = Instant.now();
            if (!forceRefresh && snapshot != null && now.isBefore(snapshot.expiresAt())) {
                return snapshot.jwkSet();
            }

            String jwksUrl = SecurityPropertyResolver.jwksUrl(properties);
            if (jwksUrl == null || jwksUrl.isBlank()) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_JWKS_URL is not configured");
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(jwksUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch JWKS");
                }
                JWKSet jwkSet = JWKSet.parse(response.body());
                long ttlSeconds = SecurityPropertyResolver.jwksCacheSeconds(properties);
                cachedJwks = new CachedJwks(jwkSet, Instant.now().plusSeconds(ttlSeconds));
                return jwkSet;
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to load JWKS: " + e.getMessage());
            }
        }
    }

    private boolean verifySignature(SignedJWT jwt, JWKSet jwkSet, JWSAlgorithm algorithm) {
        List<JWK> candidates = new ArrayList<>();
        String keyId = jwt.getHeader().getKeyID();

        for (JWK key : jwkSet.getKeys()) {
            if (keyId != null && !keyId.equals(key.getKeyID())) {
                continue;
            }
            if (!isCompatibleKey(algorithm, key)) {
                continue;
            }
            candidates.add(key);
        }

        if (candidates.isEmpty()) {
            return false;
        }

        for (JWK candidate : candidates) {
            JWSVerifier verifier = buildVerifier(candidate);
            if (verifier == null) {
                continue;
            }
            try {
                if (jwt.verify(verifier)) {
                    return true;
                }
            } catch (JOSEException ignored) {
                // try next candidate key
            }
        }
        return false;
    }

    private boolean isCompatibleKey(JWSAlgorithm algorithm, JWK key) {
        if (JWSAlgorithm.RS256.equals(algorithm)) {
            return key instanceof RSAKey;
        }
        if (JWSAlgorithm.ES256.equals(algorithm)) {
            return key instanceof ECKey;
        }
        return false;
    }

    private JWSVerifier buildVerifier(JWK key) {
        try {
            if (key instanceof RSAKey rsaKey) {
                return new RSASSAVerifier(rsaKey.toRSAPublicKey());
            }
            if (key instanceof ECKey ecKey) {
                return new ECDSAVerifier(ecKey.toECPublicKey());
            }
            return null;
        } catch (JOSEException e) {
            return null;
        }
    }

    private record CachedJwks(JWKSet jwkSet, Instant expiresAt) {
    }
}
