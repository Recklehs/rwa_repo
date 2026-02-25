\set ON_ERROR_STOP on

DO $$
DECLARE
  invalid_count bigint;
BEGIN
  SELECT COUNT(*) INTO invalid_count
  FROM outbox_tx
  WHERE status IS NULL
     OR status NOT IN ('CREATED', 'SIGNED', 'SENT', 'MINED', 'FAILED', 'REPLACED');

  IF invalid_count > 0 THEN
    RAISE EXCEPTION 'T3 failed: invalid outbox_tx statuses found: %', invalid_count;
  END IF;
END $$;

SELECT 'T3 outbox state machine check passed' AS result;
