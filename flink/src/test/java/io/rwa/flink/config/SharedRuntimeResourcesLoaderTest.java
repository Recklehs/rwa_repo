package io.rwa.flink.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

class SharedRuntimeResourcesLoaderTest {

    @Test
    void load_readsContractsConstantsAndAbiDerivedEvents() {
        FlinkIndexerConfig config = new FlinkIndexerConfig(
            Path.of("../shared"),
            91342L,
            "localhost:9092",
            "chain.logs.raw",
            "rwa-flink-indexer-test",
            "",
            "",
            "",
            "",
            "jdbc:postgresql://localhost:5432/rwa",
            "rwa",
            "rwa_password",
            "public",
            10000L,
            1,
            1,
            FilterMode.ALL
        );

        SharedRuntimeResources resources = new SharedRuntimeResourcesLoader(new ObjectMapper()).load(config);

        assertEquals(91342L, resources.chainId());
        assertTrue(resources.shareScale().signum() > 0);
        assertEquals(5, resources.eventByAddressTopic().size());

        String marketAddress = resources.contractAddress("FixedPriceMarketDvP");
        String listedTopic = Hash
            .sha3String("Listed(uint256,address,address,uint256,address,uint256,uint256)")
            .toLowerCase();

        AbiEventDefinition listed = resources.findEvent(marketAddress, listedTopic).orElseThrow();
        assertEquals("Listed", listed.eventName());
        assertFalse(listed.inputs().isEmpty());

        String shareAddress = resources.contractAddress("PropertyShare1155");
        String transferSingleTopic = Hash
            .sha3String("TransferSingle(address,address,address,uint256,uint256)")
            .toLowerCase();

        assertTrue(resources.findEvent(shareAddress, transferSingleTopic).isPresent());
    }
}
