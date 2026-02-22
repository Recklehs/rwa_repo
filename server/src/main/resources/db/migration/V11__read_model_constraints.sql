-- Enforce read-model idempotency constraints required by Flink indexer (SPEC4.1.1).

-- processed_events: dedup by event_key (chainId:txHashLower:logIndex)
DELETE FROM processed_events p
USING processed_events d
WHERE p.id < d.id
  AND p.event_key = d.event_key;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'processed_events'::regclass
      AND conname = 'uq_processed_events_event_key'
  ) THEN
    ALTER TABLE processed_events
      ADD CONSTRAINT uq_processed_events_event_key UNIQUE (event_key);
  END IF;
END $$;

-- trades: one Bought record per tx_hash
DELETE FROM trades t
USING trades d
WHERE t.id < d.id
  AND t.tx_hash = d.tx_hash;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'trades'::regclass
      AND conname = 'uq_trades_tx_hash'
  ) THEN
    ALTER TABLE trades
      ADD CONSTRAINT uq_trades_tx_hash UNIQUE (tx_hash);
  END IF;
END $$;

-- balances: multiple token_ids per owner are required
ALTER TABLE balances DROP CONSTRAINT IF EXISTS balances_pkey;
ALTER TABLE balances
  ADD CONSTRAINT balances_pkey PRIMARY KEY (owner, token_id);

CREATE INDEX IF NOT EXISTS idx_balances_token_id ON balances(token_id);
CREATE INDEX IF NOT EXISTS idx_balances_owner ON balances(owner);

-- Flink inserts explicit on-chain listingId values into listings.id.
SELECT setval(
  pg_get_serial_sequence('listings', 'id'),
  COALESCE((SELECT MAX(id) FROM listings), 1),
  true
);
