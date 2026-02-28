package com.example.cryptoorder.common.idempotency;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class IdempotencyCryptoService {

    private static final int GCM_TAG_BIT_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private final IdempotencyProperties idempotencyProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        String base64Key = idempotencyProperties.getResponseEncryptionKeyBase64();
        if (base64Key != null && !base64Key.isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(base64Key.replaceAll("\\s", ""));
            validateKeySize(decoded.length);
            this.secretKey = new SecretKeySpec(decoded, "AES");
            return;
        }

        if (!idempotencyProperties.isAllowEphemeralEncryptionKey()) {
            throw new IllegalStateException(
                    "idempotency.response-encryption-key-base64가 필요합니다. " +
                            "로컬 테스트에서만 idempotency.allow-ephemeral-encryption-key=true를 사용하세요."
            );
        }

        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(256);
            this.secretKey = keyGenerator.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Idempotency 암호화 키 초기화에 실패했습니다.", e);
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BIT_LENGTH, iv));
            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] merged = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, merged, 0, iv.length);
            System.arraycopy(cipherBytes, 0, merged, iv.length, cipherBytes.length);
            return Base64.getEncoder().encodeToString(merged);
        } catch (Exception e) {
            throw new IllegalStateException("Idempotency 응답 암호화에 실패했습니다.", e);
        }
    }

    public String decrypt(String cipherTextBase64) {
        if (cipherTextBase64 == null) {
            return null;
        }

        try {
            byte[] merged = Base64.getDecoder().decode(cipherTextBase64);
            byte[] iv = Arrays.copyOfRange(merged, 0, GCM_IV_LENGTH);
            byte[] cipherBytes = Arrays.copyOfRange(merged, GCM_IV_LENGTH, merged.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BIT_LENGTH, iv));
            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Idempotency 응답 복호화에 실패했습니다.", e);
        }
    }

    private void validateKeySize(int size) {
        if (size != 16 && size != 24 && size != 32) {
            throw new IllegalStateException("AES 키 길이는 16/24/32바이트여야 합니다.");
        }
    }
}
