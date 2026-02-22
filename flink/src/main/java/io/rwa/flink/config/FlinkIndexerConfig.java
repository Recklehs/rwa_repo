package io.rwa.flink.config;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Map;

public record FlinkIndexerConfig(
    Path sharedDirPath,
    long chainId,
    String kafkaBootstrapServers,
    String kafkaTopic,
    String kafkaGroupId,
    String kafkaSecurityProtocol,
    String kafkaSaslMechanism,
    String kafkaSaslJaasConfig,
    String kafkaClientDnsLookup,
    String dbUrl,
    String dbUser,
    String dbPassword,
    long checkpointIntervalMs,
    int parallelism,
    int sinkParallelism,
    FilterMode filterMode
) implements Serializable {

    public static FlinkIndexerConfig fromEnv(Map<String, String> env) {
        Path sharedDirPath = Path.of(envOrDefault(env, "SHARED_DIR_PATH", "../shared"));
        long chainId = parseLong(envOrDefault(env, "GIWA_CHAIN_ID", "91342"), "GIWA_CHAIN_ID");

        String kafkaBootstrapServers = envOrDefault(env, "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String kafkaTopic = envOrDefault(env, "KAFKA_TOPIC", "chain.logs.raw");
        String kafkaGroupId = envOrDefault(env, "KAFKA_GROUP_ID", "rwa-flink-indexer");
        String kafkaSecurityProtocol = envOrDefault(env, "KAFKA_SECURITY_PROTOCOL", "");
        String kafkaSaslMechanism = envOrDefault(env, "KAFKA_SASL_MECHANISM", "");
        String kafkaSaslJaasConfig = envOrDefault(env, "KAFKA_SASL_JAAS_CONFIG", "");
        String kafkaClientDnsLookup = envOrDefault(env, "KAFKA_CLIENT_DNS_LOOKUP", "");

        String dbUrl = envOrDefault(env, "DB_URL", "jdbc:postgresql://localhost:5432/rwa");
        String dbUser = envOrDefault(env, "DB_USER", "rwa");
        String dbPassword = envOrDefault(env, "DB_PASSWORD", "rwa_password");

        long checkpointIntervalMs = parseLong(
            envOrDefault(env, "FLINK_CHECKPOINT_INTERVAL_MS", "10000"),
            "FLINK_CHECKPOINT_INTERVAL_MS"
        );
        int parallelism = parseInt(envOrDefault(env, "FLINK_PARALLELISM", "1"), "FLINK_PARALLELISM");
        int sinkParallelism = parseInt(envOrDefault(env, "SINK_PARALLELISM", "1"), "SINK_PARALLELISM");
        FilterMode filterMode = FilterMode.parse(envOrDefault(env, "FILTER_MODE", "ALL"));

        if (chainId <= 0) {
            throw new IllegalArgumentException("GIWA_CHAIN_ID must be > 0");
        }
        if (checkpointIntervalMs <= 0) {
            throw new IllegalArgumentException("FLINK_CHECKPOINT_INTERVAL_MS must be > 0");
        }
        if (parallelism <= 0) {
            throw new IllegalArgumentException("FLINK_PARALLELISM must be > 0");
        }
        if (sinkParallelism <= 0) {
            throw new IllegalArgumentException("SINK_PARALLELISM must be > 0");
        }

        return new FlinkIndexerConfig(
            sharedDirPath,
            chainId,
            kafkaBootstrapServers,
            kafkaTopic,
            kafkaGroupId,
            kafkaSecurityProtocol,
            kafkaSaslMechanism,
            kafkaSaslJaasConfig,
            kafkaClientDnsLookup,
            dbUrl,
            dbUser,
            dbPassword,
            checkpointIntervalMs,
            parallelism,
            sinkParallelism,
            filterMode
        );
    }

    private static String envOrDefault(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static long parseLong(String value, String key) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
    }

    private static int parseInt(String value, String key) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
    }
}
