package io.rwa.flink.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecodedChainEventTest {

    @Test
    void sinkPartitionKey_usesListingKeyForMarketEvents() {
        DecodedChainEvent event = baseEvent(EventType.MARKET_BOUGHT, "91342:0xabc:1", 42L, BigInteger.valueOf(100L), List.of());
        assertEquals("listing:42", event.sinkPartitionKey());
    }

    @Test
    void sinkPartitionKey_usesTokenKeyForTransferSingle() {
        DecodedChainEvent event = baseEvent(
            EventType.ERC1155_TRANSFER_SINGLE,
            "91342:0xabc:2",
            null,
            BigInteger.valueOf(777L),
            List.of()
        );
        assertEquals("token:777", event.sinkPartitionKey());
    }

    @Test
    void sinkPartitionKey_usesFirstTokenForTransferBatch() {
        DecodedChainEvent event = baseEvent(
            EventType.ERC1155_TRANSFER_BATCH,
            "91342:0xabc:3",
            null,
            null,
            List.of(BigInteger.valueOf(9L), BigInteger.valueOf(10L))
        );
        assertEquals("token-batch:9", event.sinkPartitionKey());
    }

    private DecodedChainEvent baseEvent(
        EventType eventType,
        String eventKey,
        Long listingId,
        BigInteger tokenId,
        List<BigInteger> tokenIds
    ) {
        return new DecodedChainEvent(
            eventKey,
            eventType,
            91342L,
            1L,
            Instant.parse("2026-02-22T00:00:00Z"),
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            0L,
            "{}",
            listingId,
            tokenId,
            tokenIds,
            BigInteger.ONE,
            List.of(BigInteger.ONE),
            BigInteger.ONE,
            BigInteger.ONE,
            "0x1111111111111111111111111111111111111111",
            "0x2222222222222222222222222222222222222222",
            "0x3333333333333333333333333333333333333333",
            "0x4444444444444444444444444444444444444444",
            "0x5555555555555555555555555555555555555555",
            false,
            List.of("0x1111111111111111111111111111111111111111")
        );
    }
}
