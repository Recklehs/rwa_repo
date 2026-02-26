package io.rwa.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.flink.config.FlinkIndexerConfig;
import io.rwa.flink.config.SharedRuntimeResources;
import io.rwa.flink.config.SharedRuntimeResourcesLoader;
import io.rwa.flink.decoder.DecodeRawLogFunction;
import io.rwa.flink.model.DecodedChainEvent;
import io.rwa.flink.process.ListingFillHintProcessFunction;
import io.rwa.flink.sink.PostgresReadModelSink;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.KafkaSourceBuilder;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

public class FlinkIndexerApplication {

    public static void main(String[] args) throws Exception {
        FlinkIndexerConfig config = FlinkIndexerConfig.fromEnv(System.getenv());
        SharedRuntimeResources shared = new SharedRuntimeResourcesLoader(new ObjectMapper()).load(config);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(config.parallelism());
        env.enableCheckpointing(config.checkpointIntervalMs(), CheckpointingMode.EXACTLY_ONCE);

        CheckpointConfig checkpointConfig = env.getCheckpointConfig();
        checkpointConfig.setMinPauseBetweenCheckpoints(Math.max(1000L, config.checkpointIntervalMs() / 2L));
        checkpointConfig.setCheckpointTimeout(Math.max(60000L, config.checkpointIntervalMs() * 6L));
        checkpointConfig.setTolerableCheckpointFailureNumber(3);

        KafkaSource<String> source = buildKafkaSource(config);

        DataStream<String> raw = env.fromSource(
            source,
            WatermarkStrategy.noWatermarks(),
            "kafka-chain-logs-raw-source"
        );

        DataStream<DecodedChainEvent> decoded = raw
            .flatMap(new DecodeRawLogFunction(shared))
            .name("decode-chain-log-events");

        DataStream<DecodedChainEvent> marketEvents = decoded
            .filter(DecodedChainEvent::isMarketEvent)
            .name("market-events");

        DataStream<DecodedChainEvent> nonMarketEvents = decoded
            .filter(event -> !event.isMarketEvent())
            .name("erc1155-events");

        DataStream<DecodedChainEvent> marketWithFillHint = marketEvents
            .keyBy(DecodedChainEvent::listingStateKey)
            .process(new ListingFillHintProcessFunction())
            .name("listing-fill-hint");

        DataStream<DecodedChainEvent> sinkInput = marketWithFillHint
            .union(nonMarketEvents)
            .keyBy(DecodedChainEvent::sinkPartitionKey)
            .map((MapFunction<DecodedChainEvent, DecodedChainEvent>) event -> event)
            .name("sink-partition-key");

        sinkInput
            .addSink(
                new PostgresReadModelSink(
                    config.dbUrl(),
                    config.dbUser(),
                    config.dbPassword(),
                    config.dbSchema(),
                    shared.shareScale(),
                    config.filterMode()
                )
            )
            .name("postgres-read-model-sink")
            .setParallelism(config.sinkParallelism());

        env.execute("rwa-flink-indexer");
    }

    private static KafkaSource<String> buildKafkaSource(FlinkIndexerConfig config) {
        KafkaSourceBuilder<String> builder = KafkaSource
            .<String>builder()
            .setBootstrapServers(config.kafkaBootstrapServers())
            .setTopics(config.kafkaTopic())
            .setGroupId(config.kafkaGroupId())
            .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
            .setValueOnlyDeserializer(new SimpleStringSchema());

        builder.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        builder.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        putIfNotBlank(builder, CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, config.kafkaSecurityProtocol());
        putIfNotBlank(builder, SaslConfigs.SASL_MECHANISM, config.kafkaSaslMechanism());
        putIfNotBlank(builder, SaslConfigs.SASL_JAAS_CONFIG, config.kafkaSaslJaasConfig());
        putIfNotBlank(builder, CommonClientConfigs.CLIENT_DNS_LOOKUP_CONFIG, config.kafkaClientDnsLookup());

        return builder.build();
    }

    private static void putIfNotBlank(KafkaSourceBuilder<String> builder, String key, String value) {
        if (value != null && !value.isBlank()) {
            builder.setProperty(key, value);
        }
    }
}
