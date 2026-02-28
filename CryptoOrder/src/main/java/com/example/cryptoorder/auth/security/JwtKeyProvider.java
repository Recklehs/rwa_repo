package com.example.cryptoorder.auth.security;

import com.example.cryptoorder.auth.config.AuthServerProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class JwtKeyProvider {

    private final AuthServerProperties authServerProperties;

    private RSAPrivateKey rsaPrivateKey;
    private RSAPublicKey rsaPublicKey;
    private byte[] hmacSecret;

    @PostConstruct
    void init() {
        if (authServerProperties.getMode() == AuthServerProperties.Mode.JWKS) {
            initRsaKeys();
        } else {
            initHmacSecret();
        }
    }

    public RSAPrivateKey getRsaPrivateKey() {
        if (rsaPrivateKey == null) {
            throw new IllegalStateException("JWKS 모드에서만 RSA 개인키를 사용할 수 있습니다.");
        }
        return rsaPrivateKey;
    }

    public byte[] getHmacSecret() {
        if (hmacSecret == null) {
            throw new IllegalStateException("HMAC 모드에서만 HMAC 시크릿을 사용할 수 있습니다.");
        }
        return hmacSecret;
    }

    public RSAKey getPublicRsaJwk() {
        if (rsaPublicKey == null) {
            throw new IllegalStateException("JWKS 모드에서만 JWKS 공개키를 사용할 수 있습니다.");
        }

        return new RSAKey.Builder(rsaPublicKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(authServerProperties.getJwtKeyId())
                .build();
    }

    private void initRsaKeys() {
        try {
            String privateKeyBase64 = authServerProperties.getJwtPrivateKeyBase64();
            String publicKeyBase64 = authServerProperties.getJwtPublicKeyBase64();
            String privateKeyPath = authServerProperties.getJwtPrivateKeyPath();
            String publicKeyPath = authServerProperties.getJwtPublicKeyPath();
            byte[] privateBytes = resolveRsaPrivateKeyBytes(privateKeyBase64, privateKeyPath);
            byte[] publicBytes = resolveRsaPublicKeyBytes(publicKeyBase64, publicKeyPath);
            boolean hasPrivateKey = privateBytes != null;
            boolean hasPublicKey = publicBytes != null;

            if (hasPrivateKey ^ hasPublicKey) {
                throw new IllegalStateException("JWKS 모드에서는 공개키/개인키를 모두 설정해야 합니다.");
            }

            if (hasPrivateKey && hasPublicKey) {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicBytes));
                this.rsaPrivateKey = (RSAPrivateKey) privateKey;
                this.rsaPublicKey = (RSAPublicKey) publicKey;
                return;
            }

            if (!authServerProperties.isAllowEphemeralKeys()) {
                throw new IllegalStateException(
                        "JWKS 모드에서는 auth.jwt-private-key-base64/auth.jwt-public-key-base64 또는 " +
                                "auth.jwt-private-key-path/auth.jwt-public-key-path 설정이 필요합니다. " +
                                "로컬 테스트에서만 auth.allow-ephemeral-keys=true를 사용하세요."
                );
            }

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            this.rsaPrivateKey = (RSAPrivateKey) keyPair.getPrivate();
            this.rsaPublicKey = (RSAPublicKey) keyPair.getPublic();
        } catch (Exception e) {
            throw new IllegalStateException("JWT RSA 키 초기화에 실패했습니다.", e);
        }
    }

    private void initHmacSecret() {
        String secretBase64 = authServerProperties.getHmacSecretBase64();
        if (!isNotBlank(secretBase64)) {
            throw new IllegalStateException("HMAC 모드에서는 auth.hmac-secret-base64가 필수입니다.");
        }

        byte[] decoded = Base64.getDecoder().decode(stripWhitespace(secretBase64));
        if (decoded.length < 32) {
            throw new IllegalStateException("HMAC 시크릿은 최소 256-bit(32바이트) 이상이어야 합니다.");
        }
        this.hmacSecret = decoded;
    }

    private byte[] resolveRsaPrivateKeyBytes(String base64Value, String pathValue) throws IOException {
        return resolveKeyBytes(
                "auth.jwt-private-key-base64",
                "auth.jwt-private-key-path",
                base64Value,
                pathValue,
                "-----BEGIN PRIVATE KEY-----",
                "-----END PRIVATE KEY-----"
        );
    }

    private byte[] resolveRsaPublicKeyBytes(String base64Value, String pathValue) throws IOException {
        return resolveKeyBytes(
                "auth.jwt-public-key-base64",
                "auth.jwt-public-key-path",
                base64Value,
                pathValue,
                "-----BEGIN PUBLIC KEY-----",
                "-----END PUBLIC KEY-----"
        );
    }

    private byte[] resolveKeyBytes(
            String base64KeyName,
            String pathKeyName,
            String base64Value,
            String pathValue,
            String beginMarker,
            String endMarker
    ) throws IOException {
        boolean hasBase64 = isNotBlank(base64Value);
        boolean hasPath = isNotBlank(pathValue);
        if (hasBase64 && hasPath) {
            throw new IllegalStateException(base64KeyName + "와 " + pathKeyName + "는 동시에 설정할 수 없습니다.");
        }
        if (hasBase64) {
            return decodeBase64(base64Value);
        }
        if (hasPath) {
            return decodePemFile(pathValue, beginMarker, endMarker);
        }
        return null;
    }

    private byte[] decodePemFile(String pathValue, String beginMarker, String endMarker) throws IOException {
        Path path = Path.of(pathValue.trim());
        byte[] rawBytes = Files.readAllBytes(path);
        String content = new String(rawBytes, StandardCharsets.UTF_8);
        if (!content.contains("-----BEGIN")) {
            return rawBytes;
        }
        if (!content.contains(beginMarker) || !content.contains(endMarker)) {
            throw new IllegalStateException("PEM 파일 형식이 올바르지 않습니다. path=" + path);
        }

        String body = content
                .replace(beginMarker, "")
                .replace(endMarker, "");
        return decodeBase64(body);
    }

    private byte[] decodeBase64(String value) {
        return Base64.getDecoder().decode(stripWhitespace(value));
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String stripWhitespace(String value) {
        return value.replaceAll("\\s", "");
    }
}
