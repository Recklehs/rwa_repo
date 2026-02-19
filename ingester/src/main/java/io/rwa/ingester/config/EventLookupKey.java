package io.rwa.ingester.config;

import java.util.Locale;

public record EventLookupKey(String address, String topic0) {

    public static EventLookupKey of(String address, String topic0) {
        return new EventLookupKey(normalize(address), normalize(topic0));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
