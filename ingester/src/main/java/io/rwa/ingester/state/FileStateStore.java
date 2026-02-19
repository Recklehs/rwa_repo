package io.rwa.ingester.state;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public class FileStateStore implements StateStore {

    private final Path stateFilePath;

    public FileStateStore(Path stateFilePath) {
        this.stateFilePath = stateFilePath;
    }

    @Override
    public synchronized Optional<BigInteger> loadLastProcessedBlock() {
        if (!Files.exists(stateFilePath)) {
            return Optional.empty();
        }
        try {
            String value = Files.readString(stateFilePath, StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new BigInteger(value));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read state file: " + stateFilePath, e);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid block number in state file: " + stateFilePath, e);
        }
    }

    @Override
    public synchronized void saveLastProcessedBlock(BigInteger blockNumber) {
        try {
            Path parent = stateFilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                stateFilePath,
                blockNumber.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist state file: " + stateFilePath, e);
        }
    }
}
