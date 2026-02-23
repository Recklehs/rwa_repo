package io.rwa.server.security;

import io.rwa.server.config.RwaProperties;
import java.util.List;
import java.util.stream.Stream;

final class SecurityPropertyResolver {

    private static final List<String> DEFAULT_AUTH_REQUIRED_PATHS = List.of(
        "/trade/**",
        "/me/**",
        "/wallet/**",
        "/tx/**",
        "/users/**/holdings"
    );
    private static final List<String> DEFAULT_INTERNAL_PATHS = List.of("/internal/**");

    private SecurityPropertyResolver() {
    }

    static String authMode(RwaProperties properties) {
        String mode = auth(properties).getMode();
        if (mode == null || mode.isBlank()) {
            return "JWKS";
        }
        return mode.trim().toUpperCase();
    }

    static String jwtIssuer(RwaProperties properties) {
        String issuer = auth(properties).getJwtIssuer();
        return (issuer == null || issuer.isBlank()) ? "rwa-id-server" : issuer;
    }

    static String jwtAudience(RwaProperties properties) {
        String audience = auth(properties).getJwtAudience();
        return (audience == null || audience.isBlank()) ? "rwa-custody" : audience;
    }

    static long clockSkewSeconds(RwaProperties properties) {
        long value = auth(properties).getClockSkewSeconds();
        return value < 0 ? 60L : value;
    }

    static String jwksUrl(RwaProperties properties) {
        return auth(properties).getJwksUrl();
    }

    static long jwksCacheSeconds(RwaProperties properties) {
        long value = auth(properties).getJwksCacheSeconds();
        return value <= 0 ? 600L : value;
    }

    static String hmacSecretBase64(RwaProperties properties) {
        return auth(properties).getHmacSecretBase64();
    }

    static boolean localAuthEnabled(RwaProperties properties) {
        return auth(properties).isLocalAuthEnabled();
    }

    static List<String> authRequiredPaths(RwaProperties properties) {
        List<String> configuredPaths = auth(properties).getRequiredPaths();
        if (configuredPaths == null || configuredPaths.isEmpty()) {
            return DEFAULT_AUTH_REQUIRED_PATHS;
        }

        // Baseline protected paths are always enforced even when env overrides add custom paths.
        return Stream.concat(DEFAULT_AUTH_REQUIRED_PATHS.stream(), configuredPaths.stream())
            .filter(path -> path != null && !path.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    static String serviceTokenHeader(RwaProperties properties) {
        if (properties == null || properties.getServiceTokenHeader() == null || properties.getServiceTokenHeader().isBlank()) {
            return "X-Service-Token";
        }
        return properties.getServiceTokenHeader();
    }

    static String serviceToken(RwaProperties properties) {
        return properties == null ? null : properties.getServiceToken();
    }

    static List<String> internalPaths(RwaProperties properties) {
        if (properties == null) {
            return DEFAULT_INTERNAL_PATHS;
        }
        List<String> paths = properties.getInternalPaths();
        if (paths == null || paths.isEmpty()) {
            return DEFAULT_INTERNAL_PATHS;
        }
        return paths;
    }

    private static RwaProperties.Auth auth(RwaProperties properties) {
        if (properties == null || properties.getAuth() == null) {
            return new RwaProperties.Auth();
        }
        return properties.getAuth();
    }
}
