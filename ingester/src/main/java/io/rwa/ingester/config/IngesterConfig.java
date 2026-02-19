package io.rwa.ingester.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public record IngesterConfig(
    Path sharedDirPath,
    String rpcUrl,
    long chainId,
    String network,
    String kafkaBootstrapServers,
    String kafkaTopic,
    long pollIntervalMs,
    int maxBlockRange,
    StateStoreType stateStoreType,
    Path stateFilePath,
    String dbUrl,
    String dbUser,
    String dbPassword,
    long startBlock
) {

    public static IngesterConfig fromEnv(Map<String, String> env) {
        Path sharedDirPath = Path.of(envOrDefault(env, "SHARED_DIR_PATH", "../shared"));
        String rpcUrl = envOrDefault(env, "GIWA_RPC_URL", "https://sepolia-rpc.giwa.io");
        long chainId = parseLong(envOrDefault(env, "GIWA_CHAIN_ID", "91342"), "GIWA_CHAIN_ID");
        String network = envOrDefault(env, "NETWORK_NAME", "giwa-sepolia");
        String kafkaBootstrapServers = envOrDefault(env, "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String kafkaTopic = envOrDefault(env, "KAFKA_TOPIC", "chain.logs.raw");
        long pollIntervalMs = parseLong(envOrDefault(env, "POLL_INTERVAL_MS", "5000"), "POLL_INTERVAL_MS");
        int maxBlockRange = parseInt(envOrDefault(env, "MAX_BLOCK_RANGE", "2000"), "MAX_BLOCK_RANGE");
        StateStoreType stateStoreType = parseStateStoreType(envOrDefault(env, "STATE_STORE", "file"));
        Path stateFilePath = Path.of(envOrDefault(env, "STATE_FILE_PATH", "./state/lastProcessedBlock.txt"));
        String dbUrl = env.getOrDefault("DB_URL", "");
        String dbUser = env.getOrDefault("DB_USER", "");
        String dbPassword = env.getOrDefault("DB_PASSWORD", "");
        long startBlock = parseLong(envOrDefault(env, "START_BLOCK", "0"), "START_BLOCK");

        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("POLL_INTERVAL_MS must be > 0");
        }
        if (maxBlockRange <= 0) {
            throw new IllegalArgumentException("MAX_BLOCK_RANGE must be > 0");
        }
        if (chainId <= 0) {
            throw new IllegalArgumentException("GIWA_CHAIN_ID must be > 0");
        }
        if (stateStoreType == StateStoreType.POSTGRES && (dbUrl.isBlank() || dbUser.isBlank())) {
            throw new IllegalArgumentException("STATE_STORE=postgres requires DB_URL and DB_USER");
        }

        return new IngesterConfig(
            sharedDirPath,
            rpcUrl,
            chainId,
            network,
            kafkaBootstrapServers,
            kafkaTopic,
            pollIntervalMs,
            maxBlockRange,
            stateStoreType,
            stateFilePath,
            dbUrl,
            dbUser,
            dbPassword,
            startBlock
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

    private static StateStoreType parseStateStoreType(String value) {
        try {
            return StateStoreType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("STATE_STORE must be one of: postgres, file");
        }
    }

    public enum StateStoreType {
        POSTGRES,
        FILE
    }
}
