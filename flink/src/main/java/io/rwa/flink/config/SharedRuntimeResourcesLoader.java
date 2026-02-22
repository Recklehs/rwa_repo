package io.rwa.flink.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.web3j.crypto.Hash;

public class SharedRuntimeResourcesLoader {

    private static final Set<String> REQUIRED_CONTRACTS = Set.of(
        "MockUSD",
        "PropertyRegistry",
        "PropertyShare1155",
        "PropertyTokenizer",
        "FixedPriceMarketDvP"
    );

    private static final Map<String, List<String>> REQUIRED_EVENTS = Map.of(
        "FixedPriceMarketDvP",
        List.of("Listed", "Bought", "Cancelled"),
        "PropertyShare1155",
        List.of("TransferSingle", "TransferBatch")
    );

    private final ObjectMapper objectMapper;

    public SharedRuntimeResourcesLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SharedRuntimeResources load(FlinkIndexerConfig config) {
        try {
            Path sharedDir = config.sharedDirPath();

            JsonNode deploymentRoot = readJson(sharedDir.resolve("deployments").resolve("giwa-sepolia.json"));
            JsonNode constantsRoot = readJson(sharedDir.resolve("config").resolve("constants.json"));

            long deploymentChainId = deploymentRoot.path("chainId").asLong(-1L);
            if (deploymentChainId <= 0) {
                throw new IllegalStateException("deployments/giwa-sepolia.json must contain chainId");
            }
            if (deploymentChainId != config.chainId()) {
                throw new IllegalStateException(
                    "GIWA_CHAIN_ID mismatch. expected="
                        + config.chainId()
                        + ", deployment="
                        + deploymentChainId
                );
            }

            JsonNode contractsNode = deploymentRoot.path("contracts");
            Map<String, String> contracts = validateAndReadContracts(contractsNode);

            BigInteger shareScale = parseShareScale(constantsRoot.path("SHARE_SCALE"));
            if (shareScale.signum() <= 0) {
                throw new IllegalStateException("SHARE_SCALE must be > 0");
            }

            Map<EventLookupKey, AbiEventDefinition> eventLookup = new HashMap<>();
            for (Map.Entry<String, List<String>> contractEvents : REQUIRED_EVENTS.entrySet()) {
                String contractName = contractEvents.getKey();
                String contractAddress = contracts.get(contractName);
                JsonNode abiArray = readJson(sharedDir.resolve("abi").resolve(contractName + ".json")).path("abi");
                if (!abiArray.isArray()) {
                    throw new IllegalStateException("Invalid ABI root for " + contractName + ": missing abi array");
                }

                for (String eventName : contractEvents.getValue()) {
                    AbiEventDefinition definition = parseEventDefinition(
                        contractName,
                        contractAddress,
                        abiArray,
                        eventName
                    );
                    EventLookupKey key = EventLookupKey.of(contractAddress, definition.topic0());
                    AbiEventDefinition previous = eventLookup.putIfAbsent(key, definition);
                    if (previous != null) {
                        throw new IllegalStateException(
                            "Duplicate event mapping for "
                                + contractName
                                + " / "
                                + eventName
                                + " at "
                                + key
                        );
                    }
                }
            }

            return new SharedRuntimeResources(
                deploymentChainId,
                shareScale,
                Map.copyOf(contracts),
                Map.copyOf(eventLookup)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load shared runtime resources", e);
        }
    }

    private Map<String, String> validateAndReadContracts(JsonNode contractsNode) {
        if (contractsNode == null || !contractsNode.isObject()) {
            throw new IllegalStateException("deployment contracts object is missing");
        }

        Set<String> actual = new HashSet<>();
        contractsNode.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(REQUIRED_CONTRACTS)) {
            throw new IllegalStateException(
                "Deployment contract set mismatch. expected=" + REQUIRED_CONTRACTS + ", actual=" + actual
            );
        }

        Map<String, String> contracts = new LinkedHashMap<>();
        for (String contractName : REQUIRED_CONTRACTS) {
            String address = normalizeAddress(contractsNode.path(contractName).asText());
            if (address.isBlank()) {
                throw new IllegalStateException("Missing address for contract " + contractName);
            }
            contracts.put(contractName, address);
        }
        return contracts;
    }

    private AbiEventDefinition parseEventDefinition(
        String contractName,
        String contractAddress,
        JsonNode abiArray,
        String eventName
    ) {
        JsonNode eventNode = null;
        for (JsonNode node : abiArray) {
            if (!"event".equals(node.path("type").asText())) {
                continue;
            }
            if (!eventName.equals(node.path("name").asText())) {
                continue;
            }
            if (eventNode != null) {
                throw new IllegalStateException("Overloaded events are not supported: " + contractName + "." + eventName);
            }
            eventNode = node;
        }

        if (eventNode == null) {
            throw new IllegalStateException("Missing event in ABI: " + contractName + "." + eventName);
        }

        JsonNode inputsNode = eventNode.path("inputs");
        if (!inputsNode.isArray()) {
            throw new IllegalStateException("Event inputs must be an array: " + contractName + "." + eventName);
        }

        List<AbiInput> inputs = new ArrayList<>();
        List<String> canonicalTypes = new ArrayList<>();
        for (JsonNode inputNode : inputsNode) {
            String inputName = inputNode.path("name").asText("");
            String canonicalType = canonicalType(inputNode);
            boolean indexed = inputNode.path("indexed").asBoolean(false);
            inputs.add(new AbiInput(inputName, canonicalType, indexed));
            canonicalTypes.add(canonicalType);
        }

        String signature = eventName + "(" + String.join(",", canonicalTypes) + ")";
        String topic0 = Hash.sha3String(signature).toLowerCase(Locale.ROOT);

        return new AbiEventDefinition(
            contractName,
            contractAddress,
            eventName,
            signature,
            topic0,
            List.copyOf(inputs)
        );
    }

    private String canonicalType(JsonNode inputNode) {
        String type = inputNode.path("type").asText("");
        if (type.isBlank()) {
            throw new IllegalStateException("ABI input type is missing");
        }
        if (!type.startsWith("tuple")) {
            return type;
        }

        JsonNode components = inputNode.path("components");
        if (!components.isArray()) {
            throw new IllegalStateException("Tuple input requires components");
        }

        List<String> children = new ArrayList<>();
        for (JsonNode component : components) {
            children.add(canonicalType(component));
        }

        String suffix = type.substring("tuple".length());
        return "(" + String.join(",", children) + ")" + suffix;
    }

    private BigInteger parseShareScale(JsonNode shareScaleNode) {
        if (shareScaleNode == null || shareScaleNode.isMissingNode() || shareScaleNode.isNull()) {
            throw new IllegalStateException("Missing SHARE_SCALE in constants.json");
        }
        if (shareScaleNode.isIntegralNumber()) {
            return shareScaleNode.bigIntegerValue();
        }
        String text = shareScaleNode.asText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Invalid SHARE_SCALE value");
        }
        try {
            return new BigInteger(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("SHARE_SCALE must be numeric", e);
        }
    }

    private JsonNode readJson(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Missing required file: " + path);
        }
        return objectMapper.readTree(Files.readString(path));
    }

    private String normalizeAddress(String address) {
        if (address == null) {
            return "";
        }
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
