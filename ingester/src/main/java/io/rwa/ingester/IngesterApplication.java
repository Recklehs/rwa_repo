package io.rwa.ingester;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rwa.ingester.config.IngesterConfig;
import io.rwa.ingester.config.SharedResources;
import io.rwa.ingester.config.SharedResourcesLoader;
import io.rwa.ingester.kafka.KafkaRawLogPublisher;
import io.rwa.ingester.service.LogIngestionService;
import io.rwa.ingester.state.FileStateStore;
import io.rwa.ingester.state.PostgresStateStore;
import io.rwa.ingester.state.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

public class IngesterApplication {

    private static final Logger log = LoggerFactory.getLogger(IngesterApplication.class);

    public static void main(String[] args) {
        ObjectMapper objectMapper = new ObjectMapper();
        IngesterConfig config = IngesterConfig.fromEnv(System.getenv());
        SharedResources sharedResources = new SharedResourcesLoader(objectMapper).load(config);

        Web3j web3j = Web3j.build(new HttpService(config.rpcUrl()));
        try (
            StateStore stateStore = createStateStore(config);
            KafkaRawLogPublisher kafkaPublisher = new KafkaRawLogPublisher(config, objectMapper)
        ) {
            LogIngestionService ingestionService = new LogIngestionService(
                config,
                sharedResources,
                web3j,
                stateStore,
                kafkaPublisher,
                objectMapper
            );
            ingestionService.run();
        } finally {
            web3j.shutdown();
            log.info("Ingester shutdown complete");
        }
    }

    private static StateStore createStateStore(IngesterConfig config) {
        return switch (config.stateStoreType()) {
            case POSTGRES -> new PostgresStateStore(config.dbUrl(), config.dbUser(), config.dbPassword());
            case FILE -> new FileStateStore(config.stateFilePath());
        };
    }
}
