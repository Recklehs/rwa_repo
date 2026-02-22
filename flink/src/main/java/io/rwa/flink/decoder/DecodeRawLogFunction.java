package io.rwa.flink.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.flink.config.SharedRuntimeResources;
import io.rwa.flink.model.DecodedChainEvent;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DecodeRawLogFunction extends RichFlatMapFunction<String, DecodedChainEvent> {

    private static final Logger log = LoggerFactory.getLogger(DecodeRawLogFunction.class);

    private final SharedRuntimeResources shared;

    private transient ChainLogEventDecoder decoder;

    public DecodeRawLogFunction(SharedRuntimeResources shared) {
        this.shared = shared;
    }

    @Override
    public void open(Configuration parameters) {
        ObjectMapper objectMapper = new ObjectMapper();
        this.decoder = new ChainLogEventDecoder(objectMapper, shared);
    }

    @Override
    public void flatMap(String value, Collector<DecodedChainEvent> out) {
        try {
            decoder.decodeMessage(value).ifPresent(out::collect);
        } catch (Exception e) {
            log.warn("Skipping malformed chain log payload", e);
        }
    }
}
