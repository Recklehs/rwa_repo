package io.rwa.ingester.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.ingester.config.IngesterConfig;
import io.rwa.ingester.config.SharedResources;
import io.rwa.ingester.config.UnknownEventPolicy;
import io.rwa.ingester.state.StateStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LogIngestionServiceTest {

    @Test
    void resolveDestinationTopic_routesUnknownEventToDlqWhenPolicyIsDlq() {
        LogIngestionService service = new LogIngestionService(
            config(),
            sharedResources(UnknownEventPolicy.DLQ),
            null,
            new NoopStateStore(),
            null,
            new ObjectMapper()
        );

        assertEquals("chain.logs.dlq", service.resolveDestinationTopic(false));
    }

    @Test
    void resolveDestinationTopic_routesKnownEventToRawTopic() {
        LogIngestionService service = new LogIngestionService(
            config(),
            sharedResources(UnknownEventPolicy.DLQ),
            null,
            new NoopStateStore(),
            null,
            new ObjectMapper()
        );

        assertEquals("chain.logs.raw", service.resolveDestinationTopic(true));
    }

    @Test
    void resolveDestinationTopic_routesUnknownEventToRawWhenPolicyIsSkip() {
        LogIngestionService service = new LogIngestionService(
            config(),
            sharedResources(UnknownEventPolicy.SKIP),
            null,
            new NoopStateStore(),
            null,
            new ObjectMapper()
        );

        assertEquals("chain.logs.raw", service.resolveDestinationTopic(false));
    }

    @Test
    void publishWindowLogs_unknownEventWithFailPolicy_throwsImmediately() throws Exception {
        LogIngestionService service = new LogIngestionService(
            config(),
            sharedResources(UnknownEventPolicy.FAIL),
            null,
            new NoopStateStore(),
            null,
            new ObjectMapper()
        );

        org.web3j.protocol.core.methods.response.Log unknownLog = new org.web3j.protocol.core.methods.response.Log();
        unknownLog.setAddress("0x0000000000000000000000000000000000000001");
        unknownLog.setTopics(List.of("0x1234"));
        unknownLog.setBlockNumber("0x1");
        unknownLog.setLogIndex("0x0");

        Method publishWindowLogs = LogIngestionService.class.getDeclaredMethod("publishWindowLogs", List.class);
        publishWindowLogs.setAccessible(true);

        InvocationTargetException thrown = assertThrows(
            InvocationTargetException.class,
            () -> publishWindowLogs.invoke(service, List.of(unknownLog))
        );

        assertEquals("UnknownEventException", thrown.getCause().getClass().getSimpleName());
        assertEquals(
            "Unknown event for address/topic0: 0x0000000000000000000000000000000000000001/0x1234",
            thrown.getCause().getMessage()
        );
    }

    private IngesterConfig config() {
        return new IngesterConfig(
            Path.of("../shared"),
            "https://sepolia-rpc.giwa.io",
            91342L,
            "giwa-sepolia",
            "localhost:9092",
            "chain.logs.raw",
            "chain.logs.dlq",
            "",
            "",
            "",
            "",
            30000,
            120000,
            5,
            "",
            5000L,
            2000,
            IngesterConfig.StateStoreType.FILE,
            Path.of("./state/test-last-processed-block.txt"),
            "",
            "",
            "",
            0L
        );
    }

    private SharedResources sharedResources(UnknownEventPolicy unknownEventPolicy) {
        return new SharedResources(
            91342L,
            "giwaSepolia",
            BigInteger.TEN,
            12,
            0L,
            "chainId:txHash:logIndex",
            unknownEventPolicy,
            List.of(),
            Map.of(),
            Map.of()
        );
    }

    private static class NoopStateStore implements StateStore {

        @Override
        public Optional<BigInteger> loadLastProcessedBlock() {
            return Optional.empty();
        }

        @Override
        public void saveLastProcessedBlock(BigInteger blockNumber) {
        }
    }
}
