package io.rwa.flink.sink;

import io.rwa.flink.config.FilterMode;
import io.rwa.flink.model.DecodedChainEvent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresReadModelSink extends RichSinkFunction<DecodedChainEvent> {

    private static final Logger log = LoggerFactory.getLogger(PostgresReadModelSink.class);
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private static final String INSERT_PROCESSED_EVENT_SQL = """
        INSERT INTO processed_events(event_key, block_number, tx_hash, payload, created_at)
        VALUES (?, ?, ?, CAST(? AS jsonb), ?)
        ON CONFLICT(event_key) DO NOTHING
        """;

    private static final String UPSERT_LISTING_ACTIVE_SQL = """
        INSERT INTO listings(id, property_id, listing_status, price, created_at, updated_at)
        VALUES (?, ?, 'ACTIVE', ?, ?, ?)
        ON CONFLICT(id) DO UPDATE
        SET property_id = EXCLUDED.property_id,
            listing_status = EXCLUDED.listing_status,
            price = EXCLUDED.price,
            updated_at = EXCLUDED.updated_at
        """;

    private static final String UPDATE_LISTING_CANCELLED_SQL = """
        UPDATE listings
        SET listing_status = 'CANCELLED',
            updated_at = ?
        WHERE id = ?
        """;

    private static final String UPSERT_LISTING_CANCELLED_STUB_SQL = """
        INSERT INTO listings(id, property_id, listing_status, price, created_at, updated_at)
        VALUES (?, 'UNKNOWN', 'CANCELLED', NULL, ?, ?)
        ON CONFLICT(id) DO UPDATE
        SET listing_status = 'CANCELLED',
            updated_at = EXCLUDED.updated_at
        """;

    private static final String INSERT_TRADE_SQL = """
        INSERT INTO trades(listing_id, buyer, seller, tx_hash, traded_at, amount)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(tx_hash) DO NOTHING
        """;

    private static final String UPDATE_LISTING_TIMESTAMP_SQL = "UPDATE listings SET updated_at = ? WHERE id = ?";

    private static final String UPSERT_ACTIVE_LISTING_STUB_SQL = """
        INSERT INTO listings(id, property_id, listing_status, price, created_at, updated_at)
        VALUES (?, ?, 'ACTIVE', ?, ?, ?)
        ON CONFLICT(id) DO UPDATE
        SET updated_at = EXCLUDED.updated_at,
            price = COALESCE(listings.price, EXCLUDED.price)
        """;

    private static final String MARK_LISTING_FILLED_SQL = "UPDATE listings SET listing_status = 'FILLED', updated_at = ? WHERE id = ?";

    private static final String UPSERT_BALANCE_DELTA_SQL = """
        INSERT INTO balances(owner, token_id, amount, updated_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(owner, token_id)
        DO UPDATE SET amount = balances.amount + EXCLUDED.amount,
                      updated_at = EXCLUDED.updated_at
        """;

    private static final String SELECT_BALANCE_SQL = "SELECT amount FROM balances WHERE owner = ? AND token_id = ?";

    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final BigInteger shareScale;
    private final FilterMode filterMode;

    private transient PGSimpleDataSource dataSource;
    private transient Connection connection;

    private transient PreparedStatement insertProcessedEventStatement;
    private transient PreparedStatement upsertListingActiveStatement;
    private transient PreparedStatement updateListingCancelledStatement;
    private transient PreparedStatement upsertListingCancelledStubStatement;
    private transient PreparedStatement insertTradeStatement;
    private transient PreparedStatement updateListingTimestampStatement;
    private transient PreparedStatement upsertActiveListingStubStatement;
    private transient PreparedStatement markListingFilledStatement;
    private transient PreparedStatement upsertBalanceDeltaStatement;
    private transient PreparedStatement selectBalanceStatement;

    public PostgresReadModelSink(
        String dbUrl,
        String dbUser,
        String dbPassword,
        BigInteger shareScale,
        FilterMode filterMode
    ) {
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.shareScale = shareScale;
        this.filterMode = filterMode;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(dbUrl);
        ds.setUser(dbUser);
        ds.setPassword(dbPassword);
        this.dataSource = ds;

        ensureConnection();
        log.info("Postgres sink opened with connection reuse (filterMode={})", filterMode);
    }

    @Override
    public void invoke(DecodedChainEvent event, Context context) throws Exception {
        final int maxAttempts = 2;
        int attempt = 0;

        while (attempt < maxAttempts) {
            attempt++;
            Connection conn = ensureConnection();

            try {
                // Only mutation-eligible events are deduped so filtered events can be reprocessed during backfills.
                if (!passesFilter(conn, event)) {
                    conn.commit();
                    return;
                }

                boolean inserted = insertProcessedEvent(event);
                if (!inserted) {
                    conn.commit();
                    return;
                }

                applyReadModelMutation(event);
                conn.commit();
                return;
            } catch (SQLException e) {
                rollbackQuietly(conn);
                if (isRecoverableConnectionError(e) && attempt < maxAttempts) {
                    log.warn(
                        "DB connection error while applying eventKey={}. reconnecting and retrying once. sqlState={}",
                        event.eventKey(),
                        e.getSQLState()
                    );
                    resetConnection();
                    continue;
                }
                throw e;
            } catch (Exception e) {
                rollbackQuietly(conn);
                throw e;
            }
        }

        throw new IllegalStateException("Sink retry loop exited unexpectedly");
    }

    @Override
    public void close() throws Exception {
        resetConnection();
        super.close();
    }

    private Connection ensureConnection() throws SQLException {
        if (connection != null && !connection.isClosed() && connection.isValid(2)) {
            return connection;
        }

        resetConnection();

        connection = dataSource.getConnection();
        connection.setAutoCommit(false);

        insertProcessedEventStatement = connection.prepareStatement(INSERT_PROCESSED_EVENT_SQL);
        upsertListingActiveStatement = connection.prepareStatement(UPSERT_LISTING_ACTIVE_SQL);
        updateListingCancelledStatement = connection.prepareStatement(UPDATE_LISTING_CANCELLED_SQL);
        upsertListingCancelledStubStatement = connection.prepareStatement(UPSERT_LISTING_CANCELLED_STUB_SQL);
        insertTradeStatement = connection.prepareStatement(INSERT_TRADE_SQL);
        updateListingTimestampStatement = connection.prepareStatement(UPDATE_LISTING_TIMESTAMP_SQL);
        upsertActiveListingStubStatement = connection.prepareStatement(UPSERT_ACTIVE_LISTING_STUB_SQL);
        markListingFilledStatement = connection.prepareStatement(MARK_LISTING_FILLED_SQL);
        upsertBalanceDeltaStatement = connection.prepareStatement(UPSERT_BALANCE_DELTA_SQL);
        selectBalanceStatement = connection.prepareStatement(SELECT_BALANCE_SQL);

        return connection;
    }

    private void resetConnection() {
        closeQuietly(selectBalanceStatement);
        closeQuietly(upsertBalanceDeltaStatement);
        closeQuietly(markListingFilledStatement);
        closeQuietly(upsertActiveListingStubStatement);
        closeQuietly(updateListingTimestampStatement);
        closeQuietly(insertTradeStatement);
        closeQuietly(upsertListingCancelledStubStatement);
        closeQuietly(updateListingCancelledStatement);
        closeQuietly(upsertListingActiveStatement);
        closeQuietly(insertProcessedEventStatement);

        selectBalanceStatement = null;
        upsertBalanceDeltaStatement = null;
        markListingFilledStatement = null;
        upsertActiveListingStubStatement = null;
        updateListingTimestampStatement = null;
        insertTradeStatement = null;
        upsertListingCancelledStubStatement = null;
        updateListingCancelledStatement = null;
        upsertListingActiveStatement = null;
        insertProcessedEventStatement = null;

        closeQuietly(connection);
        connection = null;
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("Ignoring close exception", e);
        }
    }

    private void rollbackQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException rollbackError) {
            log.warn("Rollback failed", rollbackError);
        }
    }

    private boolean insertProcessedEvent(DecodedChainEvent event) throws SQLException {
        insertProcessedEventStatement.clearParameters();
        insertProcessedEventStatement.setString(1, event.eventKey());
        insertProcessedEventStatement.setLong(2, event.blockNumber());
        insertProcessedEventStatement.setString(3, event.txHash());
        insertProcessedEventStatement.setString(4, event.payloadJson());
        insertProcessedEventStatement.setObject(5, toOffsetDateTime(event));
        return insertProcessedEventStatement.executeUpdate() > 0;
    }

    private boolean passesFilter(Connection conn, DecodedChainEvent event) throws SQLException {
        if (filterMode == FilterMode.ALL) {
            return true;
        }

        List<String> addresses = normalizedParticipantAddresses(event);
        if (addresses.isEmpty()) {
            return true;
        }

        return switch (filterMode) {
            case KNOWN_WALLETS -> hasAnyKnownWallet(conn, addresses);
            case APPROVED_WALLETS -> hasAnyApprovedWallet(conn, addresses);
            case ALL -> true;
        };
    }

    private void applyReadModelMutation(DecodedChainEvent event) throws SQLException {
        switch (event.eventType()) {
            case MARKET_LISTED -> applyListed(event);
            case MARKET_BOUGHT -> applyBought(event);
            case MARKET_CANCELLED -> applyCancelled(event);
            case ERC1155_TRANSFER_SINGLE -> applyTransferSingle(event);
            case ERC1155_TRANSFER_BATCH -> applyTransferBatch(event);
        }
    }

    private void applyListed(DecodedChainEvent event) throws SQLException {
        if (event.listingId() == null || event.tokenId() == null || event.unitPrice() == null) {
            throw new IllegalStateException("Listed event missing required fields");
        }

        upsertListingActiveStatement.clearParameters();
        upsertListingActiveStatement.setLong(1, event.listingId());
        upsertListingActiveStatement.setString(2, event.tokenId().toString());
        upsertListingActiveStatement.setBigDecimal(3, toNumeric(event.unitPrice()));
        upsertListingActiveStatement.setObject(4, toOffsetDateTime(event));
        upsertListingActiveStatement.setObject(5, toOffsetDateTime(event));
        upsertListingActiveStatement.executeUpdate();

        if (event.markFilled()) {
            markListingFilled(event.listingId(), event);
        }
    }

    private void applyBought(DecodedChainEvent event) throws SQLException {
        if (event.listingId() == null || event.amount() == null || event.unitPrice() == null) {
            throw new IllegalStateException("Bought event missing required fields");
        }

        BigInteger resolvedCost = resolveCost(event);
        if (resolvedCost.signum() < 0) {
            throw new IllegalStateException("Resolved cost cannot be negative");
        }

        insertTrade(event);

        int updated = updateListingTimestamp(event.listingId(), event);
        if (updated == 0) {
            upsertActiveListingStub(event);
        }

        if (event.markFilled()) {
            markListingFilled(event.listingId(), event);
        }
    }

    private void applyCancelled(DecodedChainEvent event) throws SQLException {
        if (event.listingId() == null) {
            throw new IllegalStateException("Cancelled event missing listingId");
        }

        updateListingCancelledStatement.clearParameters();
        updateListingCancelledStatement.setObject(1, toOffsetDateTime(event));
        updateListingCancelledStatement.setLong(2, event.listingId());
        int updated = updateListingCancelledStatement.executeUpdate();

        if (updated == 0) {
            upsertListingCancelledStubStatement.clearParameters();
            upsertListingCancelledStubStatement.setLong(1, event.listingId());
            upsertListingCancelledStubStatement.setObject(2, toOffsetDateTime(event));
            upsertListingCancelledStubStatement.setObject(3, toOffsetDateTime(event));
            upsertListingCancelledStubStatement.executeUpdate();
        }
    }

    private void applyTransferSingle(DecodedChainEvent event) throws SQLException {
        if (event.tokenId() == null || event.amount() == null) {
            throw new IllegalStateException("TransferSingle event missing tokenId/value");
        }

        long tokenId = toLongExact(event.tokenId());
        BigInteger value = event.amount();

        if (!isZeroAddress(event.from())) {
            applyBalanceDelta(event.from(), tokenId, value.negate(), event);
        }
        if (!isZeroAddress(event.to())) {
            applyBalanceDelta(event.to(), tokenId, value, event);
        }
    }

    private void applyTransferBatch(DecodedChainEvent event) throws SQLException {
        if (event.tokenIds().size() != event.amounts().size()) {
            throw new IllegalStateException("TransferBatch tokenIds/amounts mismatch");
        }

        for (int i = 0; i < event.tokenIds().size(); i++) {
            long tokenId = toLongExact(event.tokenIds().get(i));
            BigInteger value = event.amounts().get(i);

            if (!isZeroAddress(event.from())) {
                applyBalanceDelta(event.from(), tokenId, value.negate(), event);
            }
            if (!isZeroAddress(event.to())) {
                applyBalanceDelta(event.to(), tokenId, value, event);
            }
        }
    }

    private void applyBalanceDelta(String owner, long tokenId, BigInteger delta, DecodedChainEvent event) throws SQLException {
        String normalizedOwner = owner.toLowerCase(Locale.ROOT);

        upsertBalanceDeltaStatement.clearParameters();
        upsertBalanceDeltaStatement.setString(1, normalizedOwner);
        upsertBalanceDeltaStatement.setLong(2, tokenId);
        upsertBalanceDeltaStatement.setBigDecimal(3, toNumeric(delta));
        upsertBalanceDeltaStatement.setObject(4, toOffsetDateTime(event));
        upsertBalanceDeltaStatement.executeUpdate();

        selectBalanceStatement.clearParameters();
        selectBalanceStatement.setString(1, normalizedOwner);
        selectBalanceStatement.setLong(2, tokenId);
        try (ResultSet rs = selectBalanceStatement.executeQuery()) {
            if (rs.next()) {
                BigDecimal amount = rs.getBigDecimal(1);
                if (amount != null && amount.signum() < 0) {
                    log.warn(
                        "Negative balance anomaly: owner={}, tokenId={}, amount={}, txHash={}, eventKey={}",
                        normalizedOwner,
                        tokenId,
                        amount,
                        event.txHash(),
                        event.eventKey()
                    );
                }
            }
        }
    }

    private void insertTrade(DecodedChainEvent event) throws SQLException {
        insertTradeStatement.clearParameters();
        insertTradeStatement.setLong(1, event.listingId());
        insertTradeStatement.setString(2, nullSafeAddress(event.buyer()));
        insertTradeStatement.setString(3, nullSafeAddress(event.seller()));
        insertTradeStatement.setString(4, event.txHash());
        insertTradeStatement.setObject(5, toOffsetDateTime(event));
        insertTradeStatement.setBigDecimal(6, toNumeric(event.amount()));
        insertTradeStatement.executeUpdate();
    }

    private int updateListingTimestamp(long listingId, DecodedChainEvent event) throws SQLException {
        updateListingTimestampStatement.clearParameters();
        updateListingTimestampStatement.setObject(1, toOffsetDateTime(event));
        updateListingTimestampStatement.setLong(2, listingId);
        return updateListingTimestampStatement.executeUpdate();
    }

    private void upsertActiveListingStub(DecodedChainEvent event) throws SQLException {
        String propertyId = event.tokenId() == null ? "UNKNOWN" : event.tokenId().toString();

        upsertActiveListingStubStatement.clearParameters();
        upsertActiveListingStubStatement.setLong(1, event.listingId());
        upsertActiveListingStubStatement.setString(2, propertyId);
        upsertActiveListingStubStatement.setBigDecimal(3, event.unitPrice() == null ? null : toNumeric(event.unitPrice()));
        upsertActiveListingStubStatement.setObject(4, toOffsetDateTime(event));
        upsertActiveListingStubStatement.setObject(5, toOffsetDateTime(event));
        upsertActiveListingStubStatement.executeUpdate();
    }

    private void markListingFilled(long listingId, DecodedChainEvent event) throws SQLException {
        markListingFilledStatement.clearParameters();
        markListingFilledStatement.setObject(1, toOffsetDateTime(event));
        markListingFilledStatement.setLong(2, listingId);
        markListingFilledStatement.executeUpdate();
    }

    private boolean hasAnyKnownWallet(Connection conn, List<String> addresses) throws SQLException {
        String sql = "SELECT 1 FROM wallets WHERE lower(address) IN (" + placeholders(addresses.size()) + ") LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindStrings(ps, addresses);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasAnyApprovedWallet(Connection conn, List<String> addresses) throws SQLException {
        String sql = """
            SELECT 1
            FROM wallets w
            JOIN users u ON u.user_id = w.user_id
            WHERE lower(w.address) IN (
            """ + placeholders(addresses.size()) + """
            )
            AND upper(u.compliance_status) = 'APPROVED'
            LIMIT 1
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindStrings(ps, addresses);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private List<String> normalizedParticipantAddresses(DecodedChainEvent event) {
        Set<String> dedup = new LinkedHashSet<>();
        for (String address : event.involvedAddresses()) {
            if (address == null || address.isBlank()) {
                continue;
            }
            String normalized = address.toLowerCase(Locale.ROOT);
            if (isZeroAddress(normalized)) {
                continue;
            }
            dedup.add(normalized);
        }
        return new ArrayList<>(dedup);
    }

    private String placeholders(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private void bindStrings(PreparedStatement ps, List<String> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            ps.setString(i + 1, values.get(i));
        }
    }

    private OffsetDateTime toOffsetDateTime(DecodedChainEvent event) {
        return OffsetDateTime.ofInstant(event.blockTimestamp(), ZoneOffset.UTC);
    }

    private long toLongExact(BigInteger value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("uint256 value does not fit BIGINT: " + value, e);
        }
    }

    private BigDecimal toNumeric(BigInteger value) {
        return new BigDecimal(value);
    }

    private BigInteger resolveCost(DecodedChainEvent event) {
        if (event.cost() != null) {
            return event.cost();
        }
        if (event.amount() == null || event.unitPrice() == null) {
            return BigInteger.ZERO;
        }
        return event.amount().multiply(event.unitPrice()).divide(shareScale);
    }

    private String nullSafeAddress(String value) {
        return value == null ? ZERO_ADDRESS : value;
    }

    private boolean isZeroAddress(String address) {
        if (address == null || address.isBlank()) {
            return true;
        }
        return ZERO_ADDRESS.equals(address.toLowerCase(Locale.ROOT));
    }

    private boolean isRecoverableConnectionError(SQLException e) {
        SQLException cursor = e;
        while (cursor != null) {
            String sqlState = cursor.getSQLState();
            if (sqlState != null && sqlState.startsWith("08")) {
                return true;
            }
            cursor = cursor.getNextException();
        }

        String message = e.getMessage();
        if (message == null) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("connection")
            || normalized.contains("broken pipe")
            || normalized.contains("socket")
            || normalized.contains("closed");
    }
}
