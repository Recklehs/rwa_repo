package com.example.cryptoorder.auth.controller;

import com.example.cryptoorder.auth.dto.AuthLoginRequest;
import com.example.cryptoorder.auth.dto.AuthRefreshRequest;
import com.example.cryptoorder.auth.dto.AuthSignupRequest;
import com.example.cryptoorder.auth.dto.AuthTokenResponse;
import com.example.cryptoorder.auth.service.AuthService;
import com.example.cryptoorder.auth.service.TokenPair;
import com.example.cryptoorder.common.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    private final AuthService authService;
    private final IdempotencyService idempotencyService;

    @PostMapping("/signup")
    public ResponseEntity<AuthTokenResponse> signup(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid AuthSignupRequest request
    ) {
        validateIdempotencyKey(idempotencyKey);
        return idempotencyService.execute(
                "POST:/auth/signup",
                idempotencyKey,
                request,
                AuthTokenResponse.class,
                () -> {
                    TokenPair pair = authService.signup(request);
                    return ResponseEntity.ok(toResponse(pair));
                }
        );
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid AuthLoginRequest request
    ) {
        validateIdempotencyKey(idempotencyKey);
        return idempotencyService.execute(
                "POST:/auth/login",
                idempotencyKey,
                request,
                AuthTokenResponse.class,
                () -> {
                    TokenPair pair = authService.login(request);
                    return ResponseEntity.ok(toResponse(pair));
                }
        );
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid AuthRefreshRequest request
    ) {
        validateIdempotencyKey(idempotencyKey);
        return idempotencyService.execute(
                "POST:/auth/refresh",
                idempotencyKey,
                request,
                AuthTokenResponse.class,
                () -> {
                    TokenPair pair = authService.refresh(request);
                    return ResponseEntity.ok(toResponse(pair));
                }
        );
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 헤더는 필수입니다.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key 길이가 너무 깁니다.");
        }
    }

    private AuthTokenResponse toResponse(TokenPair pair) {
        return new AuthTokenResponse(
                "Bearer",
                pair.accessToken(),
                pair.accessTokenExpiresIn(),
                pair.refreshToken(),
                pair.refreshTokenExpiresIn(),
                pair.userId()
        );
    }
}
