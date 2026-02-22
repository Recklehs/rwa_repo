package io.rwa.flink.config;

import java.util.Locale;

public enum FilterMode {
    ALL,
    KNOWN_WALLETS,
    APPROVED_WALLETS;

    public static FilterMode parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return FilterMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "FILTER_MODE must be one of: ALL, KNOWN_WALLETS, APPROVED_WALLETS"
            );
        }
    }
}
