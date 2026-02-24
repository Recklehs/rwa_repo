BEGIN;

TRUNCATE TABLE IF EXISTS
  processed_events,
  listings,
  trades,
  balances,
  outbox_delivery,
  outbox_event,
  outbox_tx,
  api_idempotency,
  user_orders,
  user_external_links,
  units,
  classes,
  complexes,
  wallets,
  users
RESTART IDENTITY CASCADE;

TRUNCATE TABLE IF EXISTS ingester_state;

COMMIT;
