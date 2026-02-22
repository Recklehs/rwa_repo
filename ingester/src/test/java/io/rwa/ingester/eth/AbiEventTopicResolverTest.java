package io.rwa.ingester.eth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

class AbiEventTopicResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AbiEventTopicResolver resolver = new AbiEventTopicResolver();

    @Test
    void resolveTopic0_usesAbiInputTypesInOrder() throws Exception {
        Path abiPath = Path.of("../shared/abi/PropertyShare1155.json");
        JsonNode root = objectMapper.readTree(Files.readString(abiPath));

        String topic = resolver.resolveTopic0(root.path("abi"), "TransferSingle");

        assertEquals(
            Hash.sha3String("TransferSingle(address,address,address,uint256,uint256)"),
            topic
        );
    }

    @Test
    void resolveTopic0_throwsWhenEventDoesNotExist() {
        String abiJson = """
            [
              { "type": "event", "name": "Foo", "inputs": [ { "type": "uint256" } ] }
            ]
            """;
        assertThrows(IllegalArgumentException.class, () -> {
            JsonNode abi = objectMapper.readTree(abiJson);
            resolver.resolveTopic0(abi, "Bar");
        });
    }
}
