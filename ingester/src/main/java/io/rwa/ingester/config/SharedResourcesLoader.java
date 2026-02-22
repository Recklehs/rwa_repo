package io.rwa.ingester.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.web3j.crypto.Hash;

public class SharedResourcesLoader {

    private static final Set<String> REQUIRED_CONTRACTS = Set.of(
        "MockUSD",
        "PropertyRegistry",
        "PropertyShare1155",
        "PropertyTokenizer",
        "FixedPriceMarketDvP"
    );

    private final ObjectMapper objectMapper;

    public SharedResourcesLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SharedResources load(IngesterConfig config) {
        try {
            Path sharedDir = config.sharedDirPath();
            JsonNode deploymentRoot = readJson(sharedDir.resolve("deployments").resolve("giwa-sepolia.json"));
            JsonNode constantsRoot = readJson(sharedDir.resolve("config").resolve("constants.json"));
            JsonNode signaturesRoot = readJson(sharedDir.resolve("events").resolve("signatures.json"));

            long deploymentChainId = deploymentRoot.path("chainId").asLong(config.chainId());
            if (deploymentChainId != config.chainId()) {
                throw new IllegalStateException(
                    "Deployment chainId mismatch: expected " + config.chainId() + ", got " + deploymentChainId
                );
            }
            long signaturesChainId = signaturesRoot.path("chainId").asLong(config.chainId());
            if (signaturesChainId != config.chainId()) {
                throw new IllegalStateException(
                    "signatures.json chainId mismatch: expected " + config.chainId() + ", got " + signaturesChainId
                );
            }

            JsonNode contractsNode = deploymentRoot.path("contracts");
            validateContractsNode(contractsNode);

            BigInteger shareScale = constantsRoot.path("SHARE_SCALE").bigIntegerValue();
            int confirmations = constantsRoot.path("CONFIRMATIONS").asInt(12);
            String network = signaturesRoot.path("network").asText(config.network());
            long startBlock = signaturesRoot.path("startBlock").asLong(0L);
            String dedupKeyFormat = signaturesRoot.path("dedupKeyFormat").asText("chainId:txHash:logIndex");
            UnknownEventPolicy unknownEventPolicy = UnknownEventPolicy.parse(
                signaturesRoot.path("unknownEventPolicy").asText("FAIL")
            );

            List<EventSubscription> enabledEvents = loadEnabledEvents(signaturesRoot, contractsNode);
            validateFilterCoverage(signaturesRoot, enabledEvents);
            Map<String, List<String>> topicsByAddress = buildTopicsByAddress(enabledEvents);
            Map<EventLookupKey, EventSubscription> eventByAddressAndTopic = buildEventLookup(enabledEvents);

            return new SharedResources(
                deploymentChainId,
                network,
                shareScale,
                confirmations,
                startBlock,
                dedupKeyFormat,
                unknownEventPolicy,
                List.copyOf(enabledEvents),
                topicsByAddress,
                eventByAddressAndTopic
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load shared resources", e);
        }
    }

    private List<EventSubscription> loadEnabledEvents(JsonNode signaturesRoot, JsonNode contractsNode) {
        JsonNode events = signaturesRoot.path("events");
        if (!events.isArray()) {
            throw new IllegalStateException("Invalid signatures.json: events array is missing");
        }

        List<EventSubscription> enabledEvents = new ArrayList<>();
        for (JsonNode eventNode : events) {
            if (!eventNode.path("enabled").asBoolean(false)) {
                continue;
            }
            String id = eventNode.path("id").asText();
            String contract = eventNode.path("contract").asText();
            String signature = eventNode.path("signature").asText();
            String topic0 = normalizeTopic(eventNode.path("topic0").asText());
            String address = normalizeAddress(eventNode.path("address").asText());

            if (id.isBlank() || contract.isBlank() || signature.isBlank() || topic0.isBlank() || address.isBlank()) {
                throw new IllegalStateException("Invalid signatures.json event entry: required fields are missing");
            }
            if (!REQUIRED_CONTRACTS.contains(contract)) {
                throw new IllegalStateException("signatures.json has unsupported contract: " + contract);
            }

            String deployedAddress = normalizeAddress(contractsNode.path(contract).asText());
            if (!deployedAddress.equals(address)) {
                throw new IllegalStateException(
                    "signatures.json address mismatch for "
                        + contract
                        + ": expected="
                        + deployedAddress
                        + ", actual="
                        + address
                );
            }

            String computedTopic0 = Hash.sha3String(signature).toLowerCase(Locale.ROOT);
            if (!computedTopic0.equals(topic0)) {
                throw new IllegalStateException(
                    "signatures.json topic0 mismatch for event "
                        + id
                        + ": expected="
                        + computedTopic0
                        + ", actual="
                        + topic0
                );
            }

            enabledEvents.add(new EventSubscription(id, contract, address, signature, topic0));
        }

        if (enabledEvents.isEmpty()) {
            throw new IllegalStateException("signatures.json has no enabled events");
        }

        enabledEvents.sort(Comparator.comparing(EventSubscription::id));
        return enabledEvents;
    }

    private Map<String, List<String>> buildTopicsByAddress(List<EventSubscription> enabledEvents) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (EventSubscription event : enabledEvents) {
            grouped.computeIfAbsent(event.address(), ignored -> new HashSet<>()).add(event.topic0());
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : grouped.entrySet()) {
            List<String> topics = new ArrayList<>(entry.getValue());
            topics.sort(String::compareTo);
            result.put(entry.getKey(), List.copyOf(topics));
        }
        return Map.copyOf(result);
    }

    private Map<EventLookupKey, EventSubscription> buildEventLookup(List<EventSubscription> enabledEvents) {
        Map<EventLookupKey, EventSubscription> lookup = new HashMap<>();
        for (EventSubscription event : enabledEvents) {
            EventLookupKey key = EventLookupKey.of(event.address(), event.topic0());
            EventSubscription previous = lookup.putIfAbsent(key, event);
            if (previous != null) {
                throw new IllegalStateException(
                    "Duplicate enabled event mapping for address/topic0: " + event.address() + "/" + event.topic0()
                );
            }
        }
        return Map.copyOf(lookup);
    }

    private void validateFilterCoverage(JsonNode signaturesRoot, List<EventSubscription> enabledEvents) {
        JsonNode filtersNode = signaturesRoot.path("filters");
        Set<String> filterAddresses = readStringSet(filtersNode.path("addresses"));
        Set<String> filterTopics = readStringSet(filtersNode.path("topic0"));

        for (EventSubscription event : enabledEvents) {
            if (!filterAddresses.isEmpty() && !filterAddresses.contains(event.address())) {
                throw new IllegalStateException("enabled event address missing in filters.addresses: " + event.id());
            }
            if (!filterTopics.isEmpty() && !filterTopics.contains(event.topic0())) {
                throw new IllegalStateException("enabled event topic0 missing in filters.topic0: " + event.id());
            }
        }
    }

    private Set<String> readStringSet(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Collections.emptySet();
        }
        if (!node.isArray()) {
            throw new IllegalStateException("Invalid signatures.json: filter node is not array");
        }
        Set<String> values = new HashSet<>();
        for (JsonNode item : node) {
            String value = item.asText();
            if (value == null || value.isBlank()) {
                continue;
            }
            values.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return values;
    }

    private JsonNode readJson(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Missing required file: " + path);
        }
        return objectMapper.readTree(Files.readString(path));
    }

    private void validateContractsNode(JsonNode contractsNode) {
        if (contractsNode == null || !contractsNode.isObject()) {
            throw new IllegalStateException("Invalid deployment file: contracts object is missing");
        }

        Set<String> actualNames = new HashSet<>();
        contractsNode.fieldNames().forEachRemaining(actualNames::add);

        if (!actualNames.equals(REQUIRED_CONTRACTS)) {
            throw new IllegalStateException(
                "Deployment contracts mismatch. expected=" + REQUIRED_CONTRACTS + ", actual=" + actualNames
            );
        }

        for (String contractName : REQUIRED_CONTRACTS) {
            String address = contractsNode.path(contractName).asText();
            if (address == null || address.isBlank()) {
                throw new IllegalStateException("Missing address for contract: " + contractName);
            }
        }
    }

    private String normalizeAddress(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeTopic(String topic0) {
        return topic0.trim().toLowerCase(Locale.ROOT);
    }
}
