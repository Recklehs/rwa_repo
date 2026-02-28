package com.example.cryptoorder.auth.security;

import com.example.cryptoorder.auth.config.AuthServerProperties;
import com.nimbusds.jose.jwk.JWKSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwksService {

    private final AuthServerProperties authServerProperties;
    private final JwtKeyProvider jwtKeyProvider;

    public Map<String, Object> getJwks() {
        if (authServerProperties.getMode() != AuthServerProperties.Mode.JWKS) {
            return Map.of("keys", java.util.List.of());
        }

        JWKSet jwkSet = new JWKSet(jwtKeyProvider.getPublicRsaJwk());
        return jwkSet.toJSONObject();
    }
}
