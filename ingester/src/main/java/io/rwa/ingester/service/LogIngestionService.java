package io.rwa.ingester.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.rwa.ingester.config.EventSubscription;
import io.rwa.ingester.config.IngesterConfig;
import io.rwa.ingester.config.SharedResources;
import io.rwa.ingester.config.UnknownEventPolicy;
import io.rwa.ingester.kafka.KafkaRawLogPublisher;
import io.rwa.ingester.state.StateStore;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;

public class LogIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LogIngestionService.class);

    private final IngesterConfig config;
    private final SharedResources shared;
    private final Web3j web3j;
    private final StateStore stateStore;
    private final KafkaRawLogPublisher kafkaPublisher;
    private final ObjectMapper objectMapper;

    public LogIngestionService(
        IngesterConfig config,
        SharedResources shared,
        Web3j web3j,
        StateStore stateStore,
        KafkaRawLogPublisher kafkaPublisher,
        ObjectMapper objectMapper
    ) {
        this.config = config;
        this.shared = shared;
        this.web3j = web3j;
        this.stateStore = stateStore;
        this.kafkaPublisher = kafkaPublisher;
        this.objectMapper = objectMapper;
    }

    public void run() {
        BigInteger startingLastProcessed = initializeLastProcessedBlock();
        BigInteger lastProcessedBlock = startingLastProcessed;
        int activeMaxRange = config.maxBlockRange();

        log.info(
            "Ingester started. chainId={}, rpcUrl={}, topic={}, confirmations={}, startLastProcessedBlock={}",
            shared.chainId(),
            config.rpcUrl(),
            config.kafkaTopic(),
            shared.confirmations(),
            lastProcessedBlock
        );

        while (!Thread.currentThread().isInterrupted()) {
            try {
                BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
                BigInteger safeToBlock = latest.subtract(BigInteger.valueOf(shared.confirmations()));
                BigInteger fromBlock = lastProcessedBlock.add(BigInteger.ONE);

                if (safeToBlock.compareTo(fromBlock) < 0) {
                    sleep(config.pollIntervalMs());
                    continue;
                }

                FetchWindowResult windowResult = fetchWindowWithAdaptiveRange(fromBlock, safeToBlock, activeMaxRange);
                activeMaxRange = windowResult.nextMaxRange();

                List<Log> orderedLogs = windowResult.logs();
                orderedLogs.sort(Comparator.comparing(Log::getBlockNumber).thenComparing(Log::getLogIndex));

                publishWindowLogs(orderedLogs);

                lastProcessedBlock = windowResult.windowEnd();
                stateStore.saveLastProcessedBlock(lastProcessedBlock);

                log.info(
                    "Checkpoint advanced to block {} (processedLogs={}, windowRange={}, from={}, to={})",
                    lastProcessedBlock,
                    orderedLogs.size(),
                    windowResult.windowRange(),
                    fromBlock,
                    windowResult.windowEnd()
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Ingester loop interrupted");
            } catch (Exception e) {
                log.error("Polling loop error", e);
                try {
                    sleep(config.pollIntervalMs());
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    log.info("Ingester interrupted while waiting after error");
                }
            }
        }
    }

    private BigInteger initializeLastProcessedBlock() {
        Optional<BigInteger> persisted = stateStore.loadLastProcessedBlock();
        if (persisted.isPresent()) {
            return persisted.get();
        }
        long startBlock = config.startBlock() > 0 ? config.startBlock() : Math.max(0L, shared.startBlock());
        return BigInteger.valueOf(startBlock - 1L);
    }

    private FetchWindowResult fetchWindowWithAdaptiveRange(BigInteger fromBlock, BigInteger safeToBlock, int initialRange)
        throws Exception {
        int range = Math.max(1, initialRange);

        while (true) {
            BigInteger windowEnd = min(
                fromBlock.add(BigInteger.valueOf((long) range - 1L)),
                safeToBlock
            );

            try {
                List<Log> logs = fetchLogs(fromBlock, windowEnd);
                return new FetchWindowResult(windowEnd, logs, range, range);
            } catch (Exception e) {
                if (isRangeTooLargeError(e) && range > 1) {
                    range = Math.max(1, range / 2);
                    log.warn("eth_getLogs window too large; reducing MAX_BLOCK_RANGE to {}", range);
                    continue;
                }
                throw e;
            }
        }
    }

    private List<Log> fetchLogs(BigInteger fromBlock, BigInteger toBlock) throws IOException {
        List<Log> allLogs = new ArrayList<>();

        for (Map.Entry<String, List<String>> filter : shared.topicsByAddress().entrySet()) {
            allLogs.addAll(fetchContractLogs(fromBlock, toBlock, filter.getKey(), filter.getValue()));
        }

        return allLogs;
    }

    private List<Log> fetchContractLogs(
        BigInteger fromBlock,
        BigInteger toBlock,
        String address,
        List<String> topic0List
    ) throws IOException {
        EthFilter filter = new EthFilter(
            DefaultBlockParameter.valueOf(fromBlock),
            DefaultBlockParameter.valueOf(toBlock),
            address
        );
        filter.addOptionalTopics(topic0List.toArray(String[]::new));

        EthLog response = web3j.ethGetLogs(filter).send();
        if (response.hasError()) {
            throw new IOException(
                "eth_getLogs failed(code=" + response.getError().getCode() + "): " + response.getError().getMessage()
            );
        }

        List<Log> logs = new ArrayList<>();
        for (EthLog.LogResult<?> result : response.getLogs()) {
            logs.add((Log) result.get());
        }
        return logs;
    }

    private void publishWindowLogs(List<Log> logsInWindow) throws InterruptedException {
        Map<BigInteger, String> timestampCache = new HashMap<>();
        for (Log chainLog : logsInWindow) {
            while (true) {
                try {
                    Optional<ObjectNode> envelope = buildEnvelope(chainLog, timestampCache);
                    if (envelope.isEmpty()) {
                        break;
                    }
                    kafkaPublisher.publish(Long.toString(shared.chainId()), envelope.get());
                    break;
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    log.error(
                        "Kafka publish failed at block={}, logIndex={}. Retrying without checkpoint advance.",
                        chainLog.getBlockNumber(),
                        chainLog.getLogIndex(),
                        e
                    );
                    sleep(Math.max(1000L, config.pollIntervalMs()));
                }
            }
        }
    }

    private Optional<ObjectNode> buildEnvelope(Log chainLog, Map<BigInteger, String> timestampCache) throws IOException {
        String contractAddress = normalize(chainLog.getAddress());
        String topic0 = chainLog.getTopics().isEmpty() ? "" : normalize(chainLog.getTopics().get(0));
        Optional<EventSubscription> matchedEvent = shared.findEvent(contractAddress, topic0);
        if (matchedEvent.isEmpty() && shared.unknownEventPolicy() == UnknownEventPolicy.SKIP) {
            return Optional.empty();
        }
        if (matchedEvent.isEmpty() && shared.unknownEventPolicy() == UnknownEventPolicy.FAIL) {
            throw new IllegalStateException(
                "Unknown event for address/topic0: " + contractAddress + "/" + topic0
            );
        }

        ObjectNode value = objectMapper.createObjectNode();
        value.put("chainId", shared.chainId());
        value.put("network", shared.network());
        value.put("blockNumber", chainLog.getBlockNumber().longValue());
        value.put("blockHash", chainLog.getBlockHash());
        value.put("blockTimestamp", resolveBlockTimestamp(chainLog.getBlockNumber(), timestampCache));
        value.put("txHash", chainLog.getTransactionHash());

        if (chainLog.getTransactionIndex() != null) {
            value.put("txIndex", chainLog.getTransactionIndex().longValue());
        }

        value.put("logIndex", chainLog.getLogIndex().longValue());
        value.put("contractAddress", contractAddress);

        ArrayNode topicsNode = value.putArray("topics");
        for (String topic : chainLog.getTopics()) {
            topicsNode.add(topic);
        }

        value.put("data", chainLog.getData());
        value.put("removed", false);
        value.put("dedupKey", buildDedupKey(chainLog, topic0));

        if (matchedEvent.isPresent()) {
            EventSubscription event = matchedEvent.get();
            value.put("eventKnown", true);
            value.put("eventId", event.id());
            value.put("eventContract", event.contract());
            value.put("eventSignature", event.signature());
            value.put("eventTopic0", event.topic0());
        } else {
            value.put("eventKnown", false);
            value.put("eventId", "unknown");
            value.put("eventContract", "unknown");
            value.put("eventSignature", "unknown");
            value.put("eventTopic0", topic0);
            value.put("unknownEventPolicy", shared.unknownEventPolicy().name());
        }

        value.put("ingestedAt", Instant.now().toString());
        return Optional.of(value);
    }

    private String resolveBlockTimestamp(BigInteger blockNumber, Map<BigInteger, String> cache) throws IOException {
        String cached = cache.get(blockNumber);
        if (cached != null) {
            return cached;
        }

        EthBlock blockResponse = web3j
            .ethGetBlockByNumber(DefaultBlockParameter.valueOf(blockNumber), false)
            .send();
        if (blockResponse.hasError()) {
            throw new IOException(
                "eth_getBlockByNumber failed(code=" + blockResponse.getError().getCode() + "): "
                    + blockResponse.getError().getMessage()
            );
        }
        EthBlock.Block block = blockResponse.getBlock();
        if (block == null) {
            throw new IOException("Missing block for number: " + blockNumber);
        }
        String timestamp = Instant.ofEpochSecond(block.getTimestamp().longValue()).toString();
        cache.put(blockNumber, timestamp);
        return timestamp;
    }

    private BigInteger min(BigInteger left, BigInteger right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private String buildDedupKey(Log chainLog, String topic0) {
        String format = shared.dedupKeyFormat();
        String[] tokens = format.split(":");
        List<String> values = new ArrayList<>();
        for (String token : tokens) {
            values.add(resolveDedupToken(token, chainLog, topic0));
        }
        return String.join(":", values);
    }

    private String resolveDedupToken(String token, Log chainLog, String topic0) {
        return switch (token) {
            case "chainId" -> Long.toString(shared.chainId());
            case "txHash" -> nullSafe(chainLog.getTransactionHash());
            case "logIndex" -> chainLog.getLogIndex() == null ? "" : chainLog.getLogIndex().toString();
            case "blockNumber" -> chainLog.getBlockNumber() == null ? "" : chainLog.getBlockNumber().toString();
            case "contractAddress" -> normalize(chainLog.getAddress());
            case "topic0" -> topic0;
            default -> token;
        };
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private boolean isRangeTooLargeError(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("query returned more than")
            || normalized.contains("too many results")
            || normalized.contains("request too large")
            || normalized.contains("response size exceeded")
            || normalized.contains("block range")
            || normalized.contains("limit exceeded");
    }

    private record FetchWindowResult(
        BigInteger windowEnd,
        List<Log> logs,
        int windowRange,
        int nextMaxRange
    ) {
    }
}
