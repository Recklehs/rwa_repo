package io.rwa.server.security;

public interface JwtVerifier {
    VerifiedJwt verify(String token);
}
