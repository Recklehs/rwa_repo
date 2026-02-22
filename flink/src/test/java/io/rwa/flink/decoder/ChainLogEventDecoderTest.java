package io.rwa.flink.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.rwa.flink.config.AbiEventDefinition;
import io.rwa.flink.config.FilterMode;
import io.rwa.flink.config.FlinkIndexerConfig;
import io.rwa.flink.config.SharedRuntimeResources;
import io.rwa.flink.config.SharedRuntimeResourcesLoader;
import io.rwa.flink.model.DecodedChainEvent;
import io.rwa.flink.model.EventType;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChainLogEventDecoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void decode_listedEventFromRawEnvelope() throws Exception {
        SharedRuntimeResources resources = loadSharedResources();
        AbiEventDefinition listed = resources
            .eventByAddressTopic()
            .values()
            .stream()
            .filter(event -> "Listed".equals(event.eventName()))
            .findFirst()
            .orElseThrow();

        BigInteger listingId = BigInteger.valueOf(11L);
        BigInteger tokenId = BigInteger.valueOf(12001L);
        BigInteger amount = new BigInteger("1000000000000000000");
        BigInteger unitPrice = new BigInteger("25000000000000000");
        String seller = "0x1111111111111111111111111111111111111111";
        String shareToken = resources.contractAddress("PropertyShare1155");
        String payToken = resources.contractAddress("MockUSD");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chainId", 91342);
        payload.put("blockNumber", 10L);
        payload.put("blockTimestamp", "2026-02-22T00:00:00Z");
        payload.put("txHash", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        payload.put("logIndex", 7);
        payload.put("contractAddress", listed.contractAddress());

        ArrayNode topics = payload.putArray("topics");
        topics.add(listed.topic0());
        topics.add(topicFromUint(listingId));
        topics.add(topicFromAddress(seller));
        topics.add(topicFromAddress(shareToken));

        payload.put(
            "data",
            "0x"
                + slotFromUint(tokenId)
                + slotFromAddress(payToken)
                + slotFromUint(amount)
                + slotFromUint(unitPrice)
        );
        payload.put("removed", false);

        ChainLogEventDecoder decoder = new ChainLogEventDecoder(objectMapper, resources);
        Optional<DecodedChainEvent> decoded = decoder.decodeMessage(objectMapper.writeValueAsString(payload));

        assertTrue(decoded.isPresent());
        DecodedChainEvent event = decoded.get();
        assertEquals(EventType.MARKET_LISTED, event.eventType());
        assertEquals(11L, event.listingId());
        assertEquals(tokenId, event.tokenId());
        assertEquals(amount, event.amount());
        assertEquals(unitPrice, event.unitPrice());
        assertEquals(seller.toLowerCase(Locale.ROOT), event.seller());
        assertEquals("91342:0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:7", event.eventKey());
    }

    @Test
    void decode_ignoresDifferentChainId() throws Exception {
        SharedRuntimeResources resources = loadSharedResources();
        ChainLogEventDecoder decoder = new ChainLogEventDecoder(objectMapper, resources);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chainId", 1);
        payload.put("blockNumber", 1L);
        payload.put("blockTimestamp", "2026-02-22T00:00:00Z");
        payload.put("txHash", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        payload.put("logIndex", 0);
        payload.put("contractAddress", resources.contractAddress("FixedPriceMarketDvP"));
        payload.putArray("topics");
        payload.put("data", "0x");

        Optional<DecodedChainEvent> decoded = decoder.decodeMessage(objectMapper.writeValueAsString(payload));
        assertTrue(decoded.isEmpty());
    }

    private SharedRuntimeResources loadSharedResources() {
        FlinkIndexerConfig config = new FlinkIndexerConfig(
            Path.of("../shared"),
            91342L,
            "localhost:9092",
            "chain.logs.raw",
            "rwa-flink-indexer-test",
            "",
            "",
            "",
            "",
            "jdbc:postgresql://localhost:5432/rwa",
            "rwa",
            "rwa_password",
            10000L,
            1,
            1,
            FilterMode.ALL
        );
        return new SharedRuntimeResourcesLoader(objectMapper).load(config);
    }

    private String topicFromAddress(String address) {
        return "0x" + "0".repeat(24) + stripHexPrefix(address).toLowerCase(Locale.ROOT);
    }

    private String topicFromUint(BigInteger value) {
        return "0x" + slotFromUint(value);
    }

    private String slotFromAddress(String address) {
        return "0".repeat(24) + stripHexPrefix(address).toLowerCase(Locale.ROOT);
    }

    private String slotFromUint(BigInteger value) {
        String hex = value.toString(16);
        if (hex.length() > 64) {
            throw new IllegalArgumentException("value too large for uint256");
        }
        return "0".repeat(64 - hex.length()) + hex;
    }

    private String stripHexPrefix(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            return normalized.substring(2);
        }
        return normalized;
    }
}
