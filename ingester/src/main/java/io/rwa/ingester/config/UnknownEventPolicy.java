package io.rwa.ingester.config;

import java.util.Locale;

public enum UnknownEventPolicy {
    DLQ,
    SKIP,
    FAIL;

    public static UnknownEventPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FAIL;
        }
        return UnknownEventPolicy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
