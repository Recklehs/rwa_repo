package io.rwa.flink.config;

import java.io.Serializable;

public record AbiInput(String name, String type, boolean indexed) implements Serializable {
}
