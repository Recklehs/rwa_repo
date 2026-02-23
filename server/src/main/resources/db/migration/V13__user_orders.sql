-- User order write-model for authenticated /me/orders scope (v4.2 revA).
CREATE TABLE IF NOT EXISTS user_orders (
  id BIGSERIAL PRIMARY KEY,
  seller_user_id UUID NOT NULL REFERENCES users(user_id),
  listing_id BIGINT,
  token_id NUMERIC(78,0),
  amount NUMERIC(78,0),
  unit_price NUMERIC(78,0),
  order_type TEXT NOT NULL,
  order_status TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (seller_user_id, idempotency_key, order_type)
);

CREATE INDEX IF NOT EXISTS idx_user_orders_seller_created_at
  ON user_orders (seller_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_orders_listing_id
  ON user_orders (listing_id);
