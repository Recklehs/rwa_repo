package io.rwa.ingester.config;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record SharedResources(
    long chainId,
    String network,
    BigInteger shareScale,
    int confirmations,
    long startBlock,
    String dedupKeyFormat,
    UnknownEventPolicy unknownEventPolicy,
    List<EventSubscription> enabledEvents,
    Map<String, List<String>> topicsByAddress,
    Map<EventLookupKey, EventSubscription> eventByAddressAndTopic
) {
    public Optional<EventSubscription> findEvent(String address, String topic0) {
        return Optional.ofNullable(eventByAddressAndTopic.get(EventLookupKey.of(address, topic0)));
    }
}
