package io.rwa.server.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.rwa.server.common.ApiException;
import io.rwa.server.config.RwaProperties;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class HmacJwtVerifier implements JwtVerifier {

    private final RwaProperties properties;

    public HmacJwtVerifier(RwaProperties properties) {
        this.properties = properties;
    }

    @Override
    public VerifiedJwt verify(String token) {
        SignedJWT jwt = parseToken(token);
        if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unsupported JWT algorithm");
        }

        byte[] secret = decodeSecret();
        try {
            MACVerifier verifier = new MACVerifier(secret);
            if (!jwt.verify(verifier)) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT signature mismatch");
            }
            return JwtClaimValidator.validate(jwt.getJWTClaimsSet(), properties);
        } catch (ApiException e) {
            throw e;
        } catch (JOSEException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT verification failed");
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

    private byte[] decodeSecret() {
        String base64 = SecurityPropertyResolver.hmacSecretBase64(properties);
        if (base64 == null || base64.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH_HMAC_SECRET_BASE64 is not configured");
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
}
