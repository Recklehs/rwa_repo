package io.rwa.ingester.state;

import java.math.BigInteger;
import java.util.Optional;

public interface StateStore extends AutoCloseable {

    Optional<BigInteger> loadLastProcessedBlock();

    void saveLastProcessedBlock(BigInteger blockNumber);

    @Override
    default void close() {
    }
}
