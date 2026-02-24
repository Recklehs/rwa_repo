-- Read-model consistency checks for replay/restart/fault-injection runs.
-- Run with:
--   psql -U rwa -d rwa -v ON_ERROR_STOP=1 -f experiment/sql/verify_consistency.sql

\echo '=== CONSISTENCY SUMMARY ==='
WITH checks AS (
    SELECT
        'processed_events_duplicate_event_key' AS check_name,
        COALESCE((
            SELECT COUNT(*)
            FROM (
                SELECT event_key
                FROM processed_events
                GROUP BY event_key
                HAVING COUNT(*) > 1
            ) t
        ), 0) AS failed_rows
    UNION ALL
    SELECT
        'trades_duplicate_tx_hash',
        COALESCE((
            SELECT COUNT(*)
            FROM (
                SELECT tx_hash
                FROM trades
                GROUP BY tx_hash
                HAVING COUNT(*) > 1
            ) t
        ), 0)
    UNION ALL
    SELECT
        'trades_without_processed_event',
        COALESCE((
            SELECT COUNT(*)
            FROM trades t
            LEFT JOIN processed_events p ON lower(p.tx_hash) = lower(t.tx_hash)
            WHERE p.tx_hash IS NULL
        ), 0)
    UNION ALL
    SELECT
        'trades_without_market_bought_event',
        COALESCE((
            SELECT COUNT(*)
            FROM trades t
            LEFT JOIN processed_events p
                ON lower(p.tx_hash) = lower(t.tx_hash)
               AND p.payload ->> 'eventId' = 'market.bought'
            WHERE p.tx_hash IS NULL
        ), 0)
    UNION ALL
    SELECT
        'balances_negative_amount',
        COALESCE((
            SELECT COUNT(*)
            FROM balances
            WHERE amount < 0
        ), 0)
    UNION ALL
    SELECT
        'invalid_listing_status',
        COALESCE((
            SELECT COUNT(*)
            FROM listings
            WHERE listing_status NOT IN ('ACTIVE', 'FILLED', 'CANCELLED')
        ), 0)
    UNION ALL
    SELECT
        'filled_listing_without_bought_tx',
        COALESCE((
            SELECT COUNT(*)
            FROM listings l
            LEFT JOIN trades t ON t.listing_id = l.id
            WHERE l.listing_status = 'FILLED'
              AND t.id IS NULL
        ), 0)
    UNION ALL
    SELECT
        'bought_event_count_less_than_trade_count',
        CASE
            WHEN (
                SELECT COUNT(*)
                FROM processed_events p
                WHERE p.payload ->> 'eventId' = 'market.bought'
            ) >= (
                SELECT COUNT(*)
                FROM trades
            ) THEN 0
            ELSE (
                SELECT COUNT(*) FROM trades
            ) - (
                SELECT COUNT(*) FROM processed_events p WHERE p.payload ->> 'eventId' = 'market.bought'
            )
        END
)
SELECT check_name, failed_rows
FROM checks
ORDER BY failed_rows DESC, check_name;

\echo ''
\echo '=== DETAIL: NEGATIVE BALANCES (top 20) ==='
SELECT owner, token_id, amount, updated_at
FROM balances
WHERE amount < 0
ORDER BY amount ASC
LIMIT 20;

\echo ''
\echo '=== DETAIL: TRADES WITHOUT MARKET.BOUGHT EVENT (top 20) ==='
SELECT t.id, t.listing_id, t.tx_hash, t.traded_at, t.amount
FROM trades t
LEFT JOIN processed_events p
    ON lower(p.tx_hash) = lower(t.tx_hash)
   AND p.payload ->> 'eventId' = 'market.bought'
WHERE p.tx_hash IS NULL
ORDER BY t.traded_at DESC
LIMIT 20;

\echo ''
\echo '=== DETAIL: PROCESSED EVENT COUNTS BY EVENT ID ==='
SELECT COALESCE(payload ->> 'eventId', 'unknown') AS event_id, COUNT(*) AS cnt
FROM processed_events
GROUP BY COALESCE(payload ->> 'eventId', 'unknown')
ORDER BY cnt DESC, event_id;
