package io.rwa.flink.model;

import java.io.Serializable;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record DecodedChainEvent(
    String eventKey,
    EventType eventType,
    long chainId,
    long blockNumber,
    Instant blockTimestamp,
    String txHash,
    long logIndex,
    String payloadJson,
    Long listingId,
    BigInteger tokenId,
    List<BigInteger> tokenIds,
    BigInteger amount,
    List<BigInteger> amounts,
    BigInteger unitPrice,
    BigInteger cost,
    String buyer,
    String seller,
    String operator,
    String from,
    String to,
    boolean markFilled,
    List<String> involvedAddresses
) implements Serializable {

    public DecodedChainEvent {
        eventKey = trim(eventKey);
        txHash = normalizeHex(txHash);
        payloadJson = payloadJson == null ? "{}" : payloadJson;
        buyer = normalizeAddress(buyer);
        seller = normalizeAddress(seller);
        operator = normalizeAddress(operator);
        from = normalizeAddress(from);
        to = normalizeAddress(to);

        tokenIds = tokenIds == null ? List.of() : List.copyOf(tokenIds);
        amounts = amounts == null ? List.of() : List.copyOf(amounts);
        involvedAddresses = normalizeAddresses(involvedAddresses);
    }

    public boolean isMarketEvent() {
        return eventType == EventType.MARKET_LISTED
            || eventType == EventType.MARKET_BOUGHT
            || eventType == EventType.MARKET_CANCELLED;
    }

    public String listingStateKey() {
        if (listingId == null) {
            return "";
        }
        return Long.toString(listingId);
    }

    public DecodedChainEvent withMarkFilled(boolean filled) {
        return new DecodedChainEvent(
            eventKey,
            eventType,
            chainId,
            blockNumber,
            blockTimestamp,
            txHash,
            logIndex,
            payloadJson,
            listingId,
            tokenId,
            tokenIds,
            amount,
            amounts,
            unitPrice,
            cost,
            buyer,
            seller,
            operator,
            from,
            to,
            filled,
            involvedAddresses
        );
    }

    public String sinkPartitionKey() {
        if (isMarketEvent() && listingId != null) {
            return "listing:" + listingId;
        }
        if (eventType == EventType.ERC1155_TRANSFER_SINGLE && tokenId != null) {
            return "token:" + tokenId;
        }
        if (eventType == EventType.ERC1155_TRANSFER_BATCH && !tokenIds.isEmpty()) {
            return "token-batch:" + tokenIds.get(0);
        }
        if (!eventKey.isBlank()) {
            return eventKey;
        }
        return eventType.name();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeHex(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeAddresses(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String address : addresses) {
            if (address == null || address.isBlank()) {
                continue;
            }
            normalized.add(address.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }
}
