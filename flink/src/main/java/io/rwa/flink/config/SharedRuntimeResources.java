package io.rwa.flink.config;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

public record SharedRuntimeResources(
    long chainId,
    BigInteger shareScale,
    Map<String, String> contracts,
    Map<EventLookupKey, AbiEventDefinition> eventByAddressTopic
) implements Serializable {

    public Optional<AbiEventDefinition> findEvent(String address, String topic0) {
        return Optional.ofNullable(eventByAddressTopic.get(EventLookupKey.of(address, topic0)));
    }

    public String contractAddress(String contractName) {
        String address = contracts.get(contractName);
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("Missing contract address for " + contractName);
        }
        return address;
    }
}
