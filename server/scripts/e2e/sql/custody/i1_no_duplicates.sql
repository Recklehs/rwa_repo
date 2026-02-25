\set ON_ERROR_STOP on

DO $$
DECLARE
  dup_count bigint;
BEGIN
  SELECT COUNT(*) INTO dup_count
  FROM (
    SELECT endpoint, idempotency_key
    FROM api_idempotency
    GROUP BY endpoint, idempotency_key
    HAVING COUNT(*) > 1
  ) d;

  IF dup_count > 0 THEN
    RAISE EXCEPTION 'I1 failed: duplicate api_idempotency rows found: %', dup_count;
  END IF;
END $$;

SELECT 'I1 custody duplicate check passed' AS result;
