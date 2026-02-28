package com.example.cryptoorder.auth.service;

import com.example.cryptoorder.Account.entity.User;
import com.example.cryptoorder.auth.config.AuthServerProperties;
import com.example.cryptoorder.auth.entity.RefreshToken;
import com.example.cryptoorder.auth.repository.RefreshTokenRepository;
import com.example.cryptoorder.common.api.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthServerProperties authServerProperties;

    @Transactional
    public IssuedRefreshToken issue(User user) {
        String plainToken = UUID.randomUUID() + "." + UUID.randomUUID();
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hash(plainToken))
                .expiresAt(LocalDateTime.now().plusSeconds(authServerProperties.getRefreshTokenTtlSeconds()))
                .build();
        refreshTokenRepository.save(token);

        return new IssuedRefreshToken(
                plainToken,
                authServerProperties.getRefreshTokenTtlSeconds(),
                user
        );
    }

    @Transactional
    public User rotate(String plainToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHashAndRevokedFalseForUpdate(hash(plainToken))
                .orElseThrow(() -> new UnauthorizedException("유효하지 않은 refresh token입니다."));

        if (token.isExpired(LocalDateTime.now())) {
            token.revoke();
            throw new UnauthorizedException("만료된 refresh token입니다.");
        }

        token.revoke();
        return token.getUser();
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("토큰 해시 생성에 실패했습니다.", e);
        }
    }
}
