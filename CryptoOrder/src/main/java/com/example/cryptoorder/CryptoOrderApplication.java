package com.example.cryptoorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CryptoOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoOrderApplication.class, args);
    }

}
