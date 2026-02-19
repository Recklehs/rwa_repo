package io.rwa.ingester.config;

import java.util.Locale;

public record EventSubscription(
    String id,
    String contract,
    String address,
    String signature,
    String topic0
) {
    public EventSubscription {
        id = sanitize(id);
        contract = sanitize(contract);
        address = sanitize(address).toLowerCase(Locale.ROOT);
        signature = sanitize(signature);
        topic0 = sanitize(topic0).toLowerCase(Locale.ROOT);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
