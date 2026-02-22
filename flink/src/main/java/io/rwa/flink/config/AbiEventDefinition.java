package io.rwa.flink.config;

import java.io.Serializable;
import java.util.List;

public record AbiEventDefinition(
    String contractName,
    String contractAddress,
    String eventName,
    String signature,
    String topic0,
    List<AbiInput> inputs
) implements Serializable {

    public List<AbiInput> indexedInputs() {
        return inputs.stream().filter(AbiInput::indexed).toList();
    }

    public List<AbiInput> nonIndexedInputs() {
        return inputs.stream().filter(input -> !input.indexed()).toList();
    }
}
