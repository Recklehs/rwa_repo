\set ON_ERROR_STOP on

DO $$
DECLARE
  negative_count bigint;
BEGIN
  SELECT COUNT(*) INTO negative_count
  FROM balances
  WHERE amount < 0;

  IF negative_count > 0 THEN
    RAISE EXCEPTION 'B4 failed: negative balances found: %', negative_count;
  END IF;
END $$;

SELECT 'B4 negative balance check passed' AS result;
