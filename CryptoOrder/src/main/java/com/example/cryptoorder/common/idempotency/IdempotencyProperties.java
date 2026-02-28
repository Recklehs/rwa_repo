package com.example.cryptoorder.common.idempotency;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    private long inProgressTtlSeconds = 120;

    /**
     * AES key (base64). 16/24/32-byte key를 권장한다.
     */
    private String responseEncryptionKeyBase64;

    /**
     * true일 경우 암호화 키 미설정 시 임시 키를 생성한다.
     * 운영 환경에서는 false를 권장한다.
     */
    private boolean allowEphemeralEncryptionKey = false;
}
