package com.example.cryptoorder.integration.walletcreate;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "integration.wallet-create")
public class WalletCreateIntegrationProperties {

    private boolean enabled = false;
    private String topic = "wallet-create";
    private int batchSize = 100;
    private long dispatchIntervalMs = 1_000L;
    private long retryBackoffMs = 5_000L;
    private int maxAttempts = 20;
}
