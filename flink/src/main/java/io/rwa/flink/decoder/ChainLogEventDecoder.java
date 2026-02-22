package io.rwa.flink.decoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.flink.config.AbiEventDefinition;
import io.rwa.flink.config.SharedRuntimeResources;
import io.rwa.flink.model.DecodedChainEvent;
import io.rwa.flink.model.EventType;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;

public class ChainLogEventDecoder {

    private static final BigInteger ZERO = BigInteger.ZERO;

    private static final List<TypeReference<Type>> LISTED_NON_INDEXED_TYPES = List.of(
        cast(TypeReference.create(Uint256.class)),
        cast(TypeReference.create(Address.class)),
        cast(TypeReference.create(Uint256.class)),
        cast(TypeReference.create(Uint256.class))
    );

    private static final List<TypeReference<Type>> BOUGHT_NON_INDEXED_TYPES = List.of(
        cast(TypeReference.create(Address.class)),
        cast(TypeReference.create(Uint256.class)),
        cast(TypeReference.create(Address.class)),
        cast(TypeReference.create(Uint256.class)),
        cast(TypeReference.create(Uint256.class)),
        cast(TypeReference.create(Uint256.class))
    );

    private static final List<TypeReference<Type>> TRANSFER_SINGLE_NON_INDEXED_TYPES = List.of(
        cast(TypeReference.create(Uint256.class)),
        cast(TypeReference.create(Uint256.class))
    );

    private static final List<TypeReference<Type>> TRANSFER_BATCH_NON_INDEXED_TYPES = List.of(
        cast(new TypeReference<DynamicArray<Uint256>>() {
        }),
        cast(new TypeReference<DynamicArray<Uint256>>() {
        })
    );

    private final ObjectMapper objectMapper;
    private final SharedRuntimeResources shared;

    public ChainLogEventDecoder(ObjectMapper objectMapper, SharedRuntimeResources shared) {
        this.objectMapper = objectMapper;
        this.shared = shared;
    }

    public Optional<DecodedChainEvent> decodeMessage(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            return decode(root, rawPayload);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid Kafka JSON payload", e);
        }
    }

    Optional<DecodedChainEvent> decode(JsonNode root, String rawPayload) {
        long chainId = root.path("chainId").asLong(-1L);
        if (chainId != shared.chainId()) {
            return Optional.empty();
        }

        if (root.path("removed").asBoolean(false)) {
            return Optional.empty();
        }

        String txHash = normalize(root.path("txHash").asText(""));
        if (txHash.isBlank()) {
            return Optional.empty();
        }

        long logIndex = root.path("logIndex").asLong(-1L);
        if (logIndex < 0) {
            throw new IllegalStateException("logIndex is missing or invalid");
        }

        long blockNumber = root.path("blockNumber").asLong(-1L);
        if (blockNumber < 0) {
            throw new IllegalStateException("blockNumber is missing or invalid");
        }

        Instant blockTimestamp = parseTimestamp(root.get("blockTimestamp"));

        String contractAddress = normalize(root.path("contractAddress").asText(""));
        if (contractAddress.isBlank()) {
            return Optional.empty();
        }

        List<String> topics = readTopics(root.path("topics"));
        if (topics.isEmpty()) {
            return Optional.empty();
        }

        String topic0 = normalize(topics.get(0));
        Optional<AbiEventDefinition> maybeEvent = shared.findEvent(contractAddress, topic0);
        if (maybeEvent.isEmpty()) {
            return Optional.empty();
        }

        AbiEventDefinition event = maybeEvent.get();
        String data = root.path("data").asText("0x");
        String eventKey = chainId + ":" + txHash + ":" + logIndex;

        return switch (event.eventName()) {
            case "Listed" -> Optional.of(decodeListed(
                eventKey,
                chainId,
                blockNumber,
                blockTimestamp,
                txHash,
                logIndex,
                rawPayload,
                topics,
                data
            ));
            case "Bought" -> Optional.of(decodeBought(
                eventKey,
                chainId,
                blockNumber,
                blockTimestamp,
                txHash,
                logIndex,
                rawPayload,
                topics,
                data
            ));
            case "Cancelled" -> Optional.of(decodeCancelled(
                eventKey,
                chainId,
                blockNumber,
                blockTimestamp,
                txHash,
                logIndex,
                rawPayload,
                topics
            ));
            case "TransferSingle" -> Optional.of(decodeTransferSingle(
                eventKey,
                chainId,
                blockNumber,
                blockTimestamp,
                txHash,
                logIndex,
                rawPayload,
                topics,
                data
            ));
            case "TransferBatch" -> Optional.of(decodeTransferBatch(
                eventKey,
                chainId,
                blockNumber,
                blockTimestamp,
                txHash,
                logIndex,
                rawPayload,
                topics,
                data
            ));
            default -> Optional.empty();
        };
    }

    private DecodedChainEvent decodeListed(
        String eventKey,
        long chainId,
        long blockNumber,
        Instant blockTimestamp,
        String txHash,
        long logIndex,
        String payloadJson,
        List<String> topics,
        String data
    ) {
        ensureTopics(topics, 4, "Listed");

        long listingId = uintTopicAsLong(topics.get(1));
        String seller = addressFromTopic(topics.get(2));

        List<Type> decoded = FunctionReturnDecoder.decode(data, LISTED_NON_INDEXED_TYPES);
        if (decoded.size() != 4) {
            throw new IllegalStateException("Listed data decode failed: expected 4 fields, got " + decoded.size());
        }

        BigInteger tokenId = uintValue(decoded.get(0));
        BigInteger amount = uintValue(decoded.get(2));
        BigInteger unitPrice = uintValue(decoded.get(3));

        return new DecodedChainEvent(
            eventKey,
            EventType.MARKET_LISTED,
            chainId,
            blockNumber,
            blockTimestamp,
            txHash,
            logIndex,
            payloadJson,
            listingId,
            tokenId,
            List.of(tokenId),
            amount,
            List.of(amount),
            unitPrice,
            null,
            null,
            seller,
            null,
            null,
            null,
            false,
            List.of(seller)
        );
    }

    private DecodedChainEvent decodeBought(
        String eventKey,
        long chainId,
        long blockNumber,
        Instant blockTimestamp,
        String txHash,
        long logIndex,
        String payloadJson,
        List<String> topics,
        String data
    ) {
        ensureTopics(topics, 4, "Bought");

        long listingId = uintTopicAsLong(topics.get(1));
        String buyer = addressFromTopic(topics.get(2));
        String seller = addressFromTopic(topics.get(3));

        List<Type> decoded = FunctionReturnDecoder.decode(data, BOUGHT_NON_INDEXED_TYPES);
        if (decoded.size() != 6) {
            throw new IllegalStateException("Bought data decode failed: expected 6 fields, got " + decoded.size());
        }

        BigInteger tokenId = uintValue(decoded.get(1));
        BigInteger amount = uintValue(decoded.get(3));
        BigInteger unitPrice = uintValue(decoded.get(4));
        BigInteger cost = uintValue(decoded.get(5));

        return new DecodedChainEvent(
            eventKey,
            EventType.MARKET_BOUGHT,
            chainId,
            blockNumber,
            blockTimestamp,
            txHash,
            logIndex,
            payloadJson,
            listingId,
            tokenId,
            List.of(tokenId),
            amount,
            List.of(amount),
            unitPrice,
            cost,
            buyer,
            seller,
            null,
            null,
            null,
            false,
            List.of(buyer, seller)
        );
    }

    private DecodedChainEvent decodeCancelled(
        String eventKey,
        long chainId,
        long blockNumber,
        Instant blockTimestamp,
        String txHash,
        long logIndex,
        String payloadJson,
        List<String> topics
    ) {
        ensureTopics(topics, 2, "Cancelled");
        long listingId = uintTopicAsLong(topics.get(1));

        return new DecodedChainEvent(
            eventKey,
            EventType.MARKET_CANCELLED,
            chainId,
            blockNumber,
            blockTimestamp,
            txHash,
            logIndex,
            payloadJson,
            listingId,
            null,
            List.of(),
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            List.of()
        );
    }

    private DecodedChainEvent decodeTransferSingle(
        String eventKey,
        long chainId,
        long blockNumber,
        Instant blockTimestamp,
        String txHash,
        long logIndex,
        String payloadJson,
        List<String> topics,
        String data
    ) {
        ensureTopics(topics, 4, "TransferSingle");

        String operator = addressFromTopic(topics.get(1));
        String from = addressFromTopic(topics.get(2));
        String to = addressFromTopic(topics.get(3));

        List<Type> decoded = FunctionReturnDecoder.decode(data, TRANSFER_SINGLE_NON_INDEXED_TYPES);
        if (decoded.size() != 2) {
            throw new IllegalStateException(
                "TransferSingle data decode failed: expected 2 fields, got " + decoded.size()
            );
        }

        BigInteger tokenId = uintValue(decoded.get(0));
        BigInteger value = uintValue(decoded.get(1));

        return new DecodedChainEvent(
            eventKey,
            EventType.ERC1155_TRANSFER_SINGLE,
            chainId,
            blockNumber,
            blockTimestamp,
            txHash,
            logIndex,
            payloadJson,
            null,
            tokenId,
            List.of(tokenId),
            value,
            List.of(value),
            null,
            null,
            null,
            null,
            operator,
            from,
            to,
            false,
            List.of(operator, from, to)
        );
    }

    private DecodedChainEvent decodeTransferBatch(
        String eventKey,
        long chainId,
        long blockNumber,
        Instant blockTimestamp,
        String txHash,
        long logIndex,
        String payloadJson,
        List<String> topics,
        String data
    ) {
        ensureTopics(topics, 4, "TransferBatch");

        String operator = addressFromTopic(topics.get(1));
        String from = addressFromTopic(topics.get(2));
        String to = addressFromTopic(topics.get(3));

        List<Type> decoded = FunctionReturnDecoder.decode(data, TRANSFER_BATCH_NON_INDEXED_TYPES);
        if (decoded.size() != 2) {
            throw new IllegalStateException("TransferBatch data decode failed: expected 2 fields, got " + decoded.size());
        }

        DynamicArray<Uint256> ids = asUintArray(decoded.get(0));
        DynamicArray<Uint256> values = asUintArray(decoded.get(1));
        if (ids.getValue().size() != values.getValue().size()) {
            throw new IllegalStateException(
                "TransferBatch ids/values size mismatch: "
                    + ids.getValue().size()
                    + " != "
                    + values.getValue().size()
            );
        }

        List<BigInteger> tokenIds = toBigIntegerList(ids);
        List<BigInteger> amounts = toBigIntegerList(values);

        return new DecodedChainEvent(
            eventKey,
            EventType.ERC1155_TRANSFER_BATCH,
            chainId,
            blockNumber,
            blockTimestamp,
            txHash,
            logIndex,
            payloadJson,
            null,
            null,
            tokenIds,
            null,
            amounts,
            null,
            null,
            null,
            null,
            operator,
            from,
            to,
            false,
            List.of(operator, from, to)
        );
    }

    private List<String> readTopics(JsonNode topicsNode) {
        if (topicsNode == null || !topicsNode.isArray()) {
            return List.of();
        }
        List<String> topics = new ArrayList<>();
        for (JsonNode topic : topicsNode) {
            String value = normalize(topic.asText(""));
            if (!value.isBlank()) {
                topics.add(value);
            }
        }
        return List.copyOf(topics);
    }

    private Instant parseTimestamp(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new IllegalStateException("blockTimestamp is missing");
        }

        if (node.isIntegralNumber()) {
            long value = node.asLong();
            return epochToInstant(value);
        }

        String text = node.asText("").trim();
        if (text.isBlank()) {
            throw new IllegalStateException("blockTimestamp is blank");
        }
        if (text.chars().allMatch(Character::isDigit)) {
            long value = Long.parseLong(text);
            return epochToInstant(value);
        }
        return Instant.parse(text);
    }

    private Instant epochToInstant(long value) {
        if (value > 1_000_000_000_000L) {
            return Instant.ofEpochMilli(value);
        }
        return Instant.ofEpochSecond(value);
    }

    private long uintTopicAsLong(String topic) {
        BigInteger value = uintTopicAsBigInteger(topic);
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("uint256 value does not fit BIGINT: " + value, e);
        }
    }

    private BigInteger uintTopicAsBigInteger(String topic) {
        String hex = stripHexPrefix(topic);
        if (hex.isBlank()) {
            return ZERO;
        }
        return new BigInteger(hex, 16);
    }

    private String addressFromTopic(String topic) {
        String hex = stripHexPrefix(topic);
        if (hex.length() < 40) {
            throw new IllegalStateException("Indexed address topic has invalid length: " + topic);
        }
        String address = hex.substring(hex.length() - 40);
        return "0x" + address.toLowerCase(Locale.ROOT);
    }

    private String stripHexPrefix(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return trimmed.substring(2);
        }
        return trimmed;
    }

    private BigInteger uintValue(Type decodedType) {
        if (!(decodedType instanceof Uint256 uint)) {
            throw new IllegalStateException("Expected Uint256 but got " + decodedType.getTypeAsString());
        }
        return uint.getValue();
    }

    @SuppressWarnings("unchecked")
    private DynamicArray<Uint256> asUintArray(Type value) {
        if (!(value instanceof DynamicArray<?> array)) {
            throw new IllegalStateException("Expected DynamicArray but got " + value.getTypeAsString());
        }
        return (DynamicArray<Uint256>) array;
    }

    private List<BigInteger> toBigIntegerList(DynamicArray<Uint256> values) {
        List<BigInteger> result = new ArrayList<>();
        for (Uint256 value : values.getValue()) {
            result.add(value.getValue());
        }
        return List.copyOf(result);
    }

    private void ensureTopics(List<String> topics, int expected, String eventName) {
        if (topics.size() < expected) {
            throw new IllegalStateException(
                eventName + " requires at least " + expected + " topics, got " + topics.size()
            );
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static TypeReference<Type> cast(TypeReference<? extends Type> ref) {
        return (TypeReference<Type>) ref;
    }
}
