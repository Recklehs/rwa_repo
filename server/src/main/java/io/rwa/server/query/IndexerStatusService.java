package io.rwa.server.query;

import io.rwa.server.web3.Web3FunctionService;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IndexerStatusService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Web3FunctionService web3FunctionService;

    public IndexerStatusService(NamedParameterJdbcTemplate jdbcTemplate, Web3FunctionService web3FunctionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.web3FunctionService = web3FunctionService;
    }

    public Map<String, Object> indexerStatus() {
        Map<String, Object> eventSummary = jdbcTemplate.queryForMap(
            """
                SELECT COUNT(*) AS processed_events_count,
                       MAX(block_number) AS last_processed_block,
                       MAX(created_at) AS last_processed_event_at
                FROM processed_events
                """,
            Map.of()
        );
        Map<String, Object> listingSummary = jdbcTemplate.queryForMap(
            "SELECT COUNT(*) AS listings_count FROM listings",
            Map.of()
        );
        Map<String, Object> tradeSummary = jdbcTemplate.queryForMap(
            "SELECT COUNT(*) AS trades_count FROM trades",
            Map.of()
        );

        Long lastProcessedBlock = toNullableLong(eventSummary.get("last_processed_block"));
        BigInteger latestChainBlock = null;
        Long lagBlocks = null;

        try {
            latestChainBlock = web3FunctionService.latestBlockNumber();
            if (latestChainBlock != null && lastProcessedBlock != null) {
                lagBlocks = latestChainBlock.longValue() - lastProcessedBlock;
                if (lagBlocks < 0) {
                    lagBlocks = 0L;
                }
            }
        } catch (Exception ignored) {
            // Keep endpoint resilient even when RPC is temporarily unavailable.
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("processedEventsCount", toLong(eventSummary.get("processed_events_count")));
        response.put("lastProcessedBlock", lastProcessedBlock);
        response.put("latestChainBlock", latestChainBlock);
        response.put("lagBlocks", lagBlocks);
        response.put("lastProcessedEventAt", eventSummary.get("last_processed_event_at"));
        response.put("listingsCount", toLong(listingSummary.get("listings_count")));
        response.put("tradesCount", toLong(tradeSummary.get("trades_count")));
        return response;
    }

    public Map<String, Object> dataFreshness() {
        Map<String, Object> freshness = jdbcTemplate.queryForMap(
            """
                SELECT
                  (SELECT MAX(created_at) FROM processed_events) AS last_processed_event_at,
                  (SELECT MAX(updated_at) FROM listings) AS last_listing_updated_at,
                  (SELECT MAX(traded_at) FROM trades) AS last_trade_at
                """,
            Map.of()
        );

        Instant now = Instant.now();
        Instant lastProcessed = toNullableInstant(freshness.get("last_processed_event_at"));
        Instant lastListing = toNullableInstant(freshness.get("last_listing_updated_at"));
        Instant lastTrade = toNullableInstant(freshness.get("last_trade_at"));
        Instant latest = max(lastProcessed, max(lastListing, lastTrade));
        Long freshnessSeconds = latest == null ? null : Duration.between(latest, now).getSeconds();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("now", now);
        response.put("lastProcessedEventAt", freshness.get("last_processed_event_at"));
        response.put("lastListingUpdatedAt", freshness.get("last_listing_updated_at"));
        response.put("lastTradeAt", freshness.get("last_trade_at"));
        response.put("freshnessSeconds", freshnessSeconds);
        return response;
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Long toNullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Instant toNullableInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    private Instant max(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
