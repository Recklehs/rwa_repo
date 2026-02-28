package com.example.cryptoorder.auth.service;

import java.util.UUID;

public record TokenPair(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresIn,
        long refreshTokenExpiresIn,
        UUID userId
) {
}
