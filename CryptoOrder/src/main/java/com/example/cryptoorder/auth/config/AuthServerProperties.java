package com.example.cryptoorder.auth.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "auth")
public class AuthServerProperties {

    private Mode mode = Mode.JWKS;

    @NotBlank
    private String jwtIssuer = "rwa-id-server";

    @NotBlank
    private String jwtAudience = "rwa-custody";

    private long accessTokenTtlSeconds = 900;

    private long refreshTokenTtlSeconds = 1_209_600;

    @NotBlank
    private String jwtKeyId = "member-server-key-1";

    /**
     * PKCS#8 DER base64 (옵션)
     */
    private String jwtPrivateKeyBase64;

    /**
     * PKCS#8 PEM file path (옵션)
     */
    private String jwtPrivateKeyPath;

    /**
     * X.509 DER base64 (옵션)
     */
    private String jwtPublicKeyBase64;

    /**
     * X.509 PEM file path (옵션)
     */
    private String jwtPublicKeyPath;

    /**
     * HMAC 모드에서만 사용
     */
    private String hmacSecretBase64;

    /**
     * true일 경우 키 미설정 시 메모리 임시 키를 생성한다.
     * 운영 환경에서는 false를 권장한다.
     */
    private boolean allowEphemeralKeys = false;

    public enum Mode {
        JWKS,
        HMAC
    }
}
