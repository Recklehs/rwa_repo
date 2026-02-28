package com.example.cryptoorder.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthRefreshRequest(
        @NotBlank(message = "refreshToken은 필수입니다.")
        String refreshToken
) {
}
