package com.example.cryptoorder.auth.dto;

import java.util.UUID;

public record AuthTokenResponse(
        String tokenType,
        String accessToken,
        long accessTokenExpiresIn,
        String refreshToken,
        long refreshTokenExpiresIn,
        UUID userId
) {
}
