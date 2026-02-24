-- Performance candidate indexes for read-model query hot paths.
-- NOTE: CONCURRENTLY cannot run inside a transaction block.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_balances_owner_token_positive
  ON balances (owner, token_id)
  WHERE amount > 0;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_balances_token_amount_positive
  ON balances (token_id, amount DESC, owner)
  WHERE amount > 0;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_listings_property_status_updated
  ON listings (property_id, listing_status, updated_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_listings_status_updated
  ON listings (listing_status, updated_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trades_buyer_traded_at
  ON trades (lower(buyer), traded_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trades_seller_traded_at
  ON trades (lower(seller), traded_at DESC);
