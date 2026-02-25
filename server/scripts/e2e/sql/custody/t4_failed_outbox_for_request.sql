\set ON_ERROR_STOP on

DO $$
DECLARE
  request_prefix text := :'request_prefix';
  failed_count bigint;
BEGIN
  IF request_prefix IS NULL OR length(trim(request_prefix)) = 0 THEN
    RAISE EXCEPTION 'request_prefix is required';
  END IF;

  SELECT COUNT(*) INTO failed_count
  FROM outbox_tx
  WHERE request_id LIKE request_prefix || '%'
    AND status = 'FAILED';

  IF failed_count = 0 THEN
    RAISE EXCEPTION 'T4 failed: no FAILED outbox_tx for request prefix %', request_prefix;
  END IF;
END $$;

SELECT 'T4 failed outbox check passed' AS result;
