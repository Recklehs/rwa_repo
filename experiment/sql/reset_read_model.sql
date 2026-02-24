BEGIN;

TRUNCATE TABLE IF EXISTS
  processed_events,
  listings,
  trades,
  balances
RESTART IDENTITY CASCADE;

TRUNCATE TABLE IF EXISTS ingester_state;

COMMIT;
