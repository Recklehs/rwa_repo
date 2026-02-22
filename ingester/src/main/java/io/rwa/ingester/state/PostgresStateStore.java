package io.rwa.ingester.state;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

public class PostgresStateStore implements StateStore {

    private static final String KEY_LAST_PROCESSED_BLOCK = "lastProcessedBlock";

    private final HikariDataSource dataSource;

    public PostgresStateStore(String dbUrl, String dbUser, String dbPassword) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(dbUrl);
        hikariConfig.setUsername(dbUser);
        hikariConfig.setPassword(dbPassword);
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(1);
        this.dataSource = new HikariDataSource(hikariConfig);
        initializeTable();
    }

    @Override
    public synchronized Optional<BigInteger> loadLastProcessedBlock() {
        String sql = "SELECT value FROM ingester_state WHERE key = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, KEY_LAST_PROCESSED_BLOCK);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String value = rs.getString(1);
                if (value == null || value.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new BigInteger(value));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load lastProcessedBlock from ingester_state", e);
        }
    }

    @Override
    public synchronized void saveLastProcessedBlock(BigInteger blockNumber) {
        String sql = """
            INSERT INTO ingester_state(key, value)
            VALUES (?, ?)
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
            """;
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, KEY_LAST_PROCESSED_BLOCK);
            ps.setString(2, blockNumber.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save lastProcessedBlock into ingester_state", e);
        }
    }

    private void initializeTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS ingester_state (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """;
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize ingester_state table", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
