package io.rwa.flink.decoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.rwa.flink.config.SharedRuntimeResources;
import io.rwa.flink.model.DecodedChainEvent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.Test;

class DecodeRawLogFunctionTest {

    @Test
    void flatMap_skipsMalformedPayloadWithoutThrowing() throws Exception {
        SharedRuntimeResources shared = new SharedRuntimeResources(
            91342L,
            BigInteger.TEN.pow(18),
            Map.of(),
            Map.of()
        );
        DecodeRawLogFunction function = new DecodeRawLogFunction(shared);
        function.open(new Configuration());

        List<DecodedChainEvent> emitted = new ArrayList<>();
        Collector<DecodedChainEvent> collector = new Collector<>() {
            @Override
            public void collect(DecodedChainEvent record) {
                emitted.add(record);
            }

            @Override
            public void close() {
            }
        };

        assertDoesNotThrow(() -> function.flatMap("{not-json", collector));
        assertTrue(emitted.isEmpty());
    }
}
