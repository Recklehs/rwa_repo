package com.example.cryptoorder.auth.service;

import com.example.cryptoorder.Account.entity.User;

public record IssuedRefreshToken(
        String plainToken,
        long expiresIn,
        User user
) {
}
