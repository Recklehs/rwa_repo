package io.rwa.ingester.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SharedResourcesLoaderTest {

    @Test
    void load_usesEnabledEventsFromSignaturesCatalog() {
        IngesterConfig config = new IngesterConfig(
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

        SharedResources resources = new SharedResourcesLoader(new ObjectMapper()).load(config);

        assertTrue(resources.confirmations() > 0);
        assertFalse(resources.enabledEvents().isEmpty());
        assertTrue(resources.topicsByAddress().containsKey("0x364efa0edc35e2a49d6f112f6cae28e9cc51e8c3"));

        EventSubscription listed = resources
            .findEvent(
                "0x364efa0edc35e2a49d6f112f6cae28e9cc51e8c3",
                "0x460ce49faebaba1c0cd745e325add52dfd408164ec39a634ff2ece24921da8d7"
            )
            .orElseThrow();

        assertEquals("market.listed", listed.id());
        assertEquals("FixedPriceMarketDvP", listed.contract());
    }
}
