package io.rwa.ingester.eth;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.web3j.crypto.Hash;

public class AbiEventTopicResolver {

    public String resolveTopic0(JsonNode abiArray, String eventName) {
        if (abiArray == null || !abiArray.isArray()) {
            throw new IllegalArgumentException("ABI root must contain an array");
        }

        JsonNode eventNode = null;
        for (JsonNode node : abiArray) {
            if (!"event".equals(node.path("type").asText())) {
                continue;
            }
            if (!eventName.equals(node.path("name").asText())) {
                continue;
            }
            if (eventNode != null) {
                throw new IllegalArgumentException("Overloaded event not supported: " + eventName);
            }
            eventNode = node;
        }

        if (eventNode == null) {
            throw new IllegalArgumentException("Missing event in ABI: " + eventName);
        }

        JsonNode inputs = eventNode.path("inputs");
        if (!inputs.isArray()) {
            throw new IllegalArgumentException("Event inputs must be an array for: " + eventName);
        }

        List<String> paramTypes = new ArrayList<>();
        for (JsonNode input : inputs) {
            paramTypes.add(canonicalType(input));
        }

        String signature = eventName + "(" + String.join(",", paramTypes) + ")";
        return Hash.sha3String(signature);
    }

    private String canonicalType(JsonNode inputNode) {
        String type = inputNode.path("type").asText();
        if (!type.startsWith("tuple")) {
            return type;
        }
        JsonNode components = inputNode.path("components");
        if (!components.isArray()) {
            throw new IllegalArgumentException("Tuple type requires components");
        }
        List<String> children = new ArrayList<>();
        for (JsonNode component : components) {
            children.add(canonicalType(component));
        }
        String suffix = type.substring("tuple".length());
        return "(" + String.join(",", children) + ")" + suffix;
    }
}
