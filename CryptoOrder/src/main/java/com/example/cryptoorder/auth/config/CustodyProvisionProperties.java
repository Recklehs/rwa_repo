package com.example.cryptoorder.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "custody.provision")
public class CustodyProvisionProperties {

    private boolean enabled = false;

    /**
     * 예) http://localhost:8080
     */
    private String baseUrl;

    private String path = "/internal/wallets/provision";

    /**
     * 커스터디 서버와 맞춰야 하는 내부 서비스 토큰
     */
    private String serviceToken;

    private String serviceTokenHeader = "X-Service-Token";

    private int connectTimeoutMillis = 2_000;

    private int readTimeoutMillis = 5_000;
}
