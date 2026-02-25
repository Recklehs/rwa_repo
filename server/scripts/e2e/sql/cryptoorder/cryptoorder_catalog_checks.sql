\set ON_ERROR_STOP on

-- CryptoOrder read-only contract checks for:
-- - I5: refresh token hash-only storage policy
-- - B1/B2/B3/B5: legacy account/bank data consistency
--
-- Required psql vars (pass via -v):
--   refresh_table
--   refresh_hash_column
-- Optional:
--   refresh_plain_column
--
--   legacy_account_table
--   legacy_account_id_column               (default: account_id)
--   legacy_account_balance_column          (default: balance)
--   legacy_tx_table
--   legacy_tx_account_id_column            (default: account_id)
--   legacy_tx_balance_after_column         (default: balance_after_transaction)
--   legacy_tx_created_at_column            (default: created_at)

\if :{?refresh_table}
\else
\set refresh_table ''
\endif

\if :{?refresh_hash_column}
\else
\set refresh_hash_column ''
\endif

\if :{?refresh_plain_column}
\else
\set refresh_plain_column ''
\endif

\if :{?legacy_account_table}
\else
\set legacy_account_table ''
\endif

\if :{?legacy_account_id_column}
\else
\set legacy_account_id_column 'account_id'
\endif

\if :{?legacy_account_balance_column}
\else
\set legacy_account_balance_column 'balance'
\endif

\if :{?legacy_tx_table}
\else
\set legacy_tx_table ''
\endif

\if :{?legacy_tx_account_id_column}
\else
\set legacy_tx_account_id_column 'account_id'
\endif

\if :{?legacy_tx_balance_after_column}
\else
\set legacy_tx_balance_after_column 'balance_after_transaction'
\endif

\if :{?legacy_tx_created_at_column}
\else
\set legacy_tx_created_at_column 'created_at'
\endif

DO $$
DECLARE
  refresh_table_name text := nullif(trim(:'refresh_table'), '');
  refresh_hash_col text := nullif(trim(:'refresh_hash_column'), '');
  refresh_plain_col text := nullif(trim(:'refresh_plain_column'), '');

  legacy_account_table_name text := nullif(trim(:'legacy_account_table'), '');
  legacy_account_id_col text := nullif(trim(:'legacy_account_id_column'), '');
  legacy_account_balance_col text := nullif(trim(:'legacy_account_balance_column'), '');
  legacy_tx_table_name text := nullif(trim(:'legacy_tx_table'), '');
  legacy_tx_account_id_col text := nullif(trim(:'legacy_tx_account_id_column'), '');
  legacy_tx_balance_after_col text := nullif(trim(:'legacy_tx_balance_after_column'), '');
  legacy_tx_created_at_col text := nullif(trim(:'legacy_tx_created_at_column'), '');

  refresh_reg regclass;
  account_reg regclass;
  tx_reg regclass;

  missing_count bigint;
  plain_count bigint;
  negative_count bigint;
  null_balance_after_count bigint;
  mismatch_count bigint;
BEGIN
  -- I5: refresh hash policy
  IF refresh_table_name IS NULL OR refresh_hash_col IS NULL THEN
    RAISE EXCEPTION
      'I5 check requires -v refresh_table and -v refresh_hash_column';
  END IF;

  refresh_reg := to_regclass(refresh_table_name);
  IF refresh_reg IS NULL THEN
    RAISE EXCEPTION 'I5 check failed: refresh table not found: %', refresh_table_name;
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_attribute
    WHERE attrelid = refresh_reg
      AND attname = refresh_hash_col
      AND NOT attisdropped
  ) THEN
    RAISE EXCEPTION 'I5 check failed: hash column % not found in %', refresh_hash_col, refresh_table_name;
  END IF;

  EXECUTE format('SELECT COUNT(*) FROM %s WHERE %I IS NULL OR trim(%I::text) = ''''', refresh_reg, refresh_hash_col, refresh_hash_col)
    INTO missing_count;

  IF missing_count > 0 THEN
    RAISE EXCEPTION 'I5 check failed: % refresh rows have null/blank hash column %', missing_count, refresh_hash_col;
  END IF;

  IF refresh_plain_col IS NOT NULL THEN
    IF NOT EXISTS (
      SELECT 1
      FROM pg_attribute
      WHERE attrelid = refresh_reg
        AND attname = refresh_plain_col
        AND NOT attisdropped
    ) THEN
      RAISE EXCEPTION 'I5 check failed: plain column % not found in %', refresh_plain_col, refresh_table_name;
    END IF;

    EXECUTE format('SELECT COUNT(*) FROM %s WHERE %I IS NOT NULL AND trim(%I::text) <> ''''', refresh_reg, refresh_plain_col, refresh_plain_col)
      INTO plain_count;

    IF plain_count > 0 THEN
      RAISE EXCEPTION 'I5 check failed: % rows contain non-empty plaintext token in column %', plain_count, refresh_plain_col;
    END IF;
  END IF;

  -- Legacy consistency checks (B1/B2/B3/B5)
  IF legacy_account_table_name IS NULL OR legacy_tx_table_name IS NULL THEN
    RAISE EXCEPTION
      'Legacy checks require -v legacy_account_table and -v legacy_tx_table';
  END IF;

  account_reg := to_regclass(legacy_account_table_name);
  IF account_reg IS NULL THEN
    RAISE EXCEPTION 'Legacy check failed: account table not found: %', legacy_account_table_name;
  END IF;

  tx_reg := to_regclass(legacy_tx_table_name);
  IF tx_reg IS NULL THEN
    RAISE EXCEPTION 'Legacy check failed: tx table not found: %', legacy_tx_table_name;
  END IF;

  -- Accounts must never be negative.
  EXECUTE format('SELECT COUNT(*) FROM %s WHERE %I < 0', account_reg, legacy_account_balance_col)
    INTO negative_count;

  IF negative_count > 0 THEN
    RAISE EXCEPTION 'Legacy check failed: % accounts have negative balances', negative_count;
  END IF;

  -- Transactions must record balance-after value.
  EXECUTE format('SELECT COUNT(*) FROM %s WHERE %I IS NULL', tx_reg, legacy_tx_balance_after_col)
    INTO null_balance_after_count;

  IF null_balance_after_count > 0 THEN
    RAISE EXCEPTION 'Legacy check failed: % tx rows have null balance-after column %', null_balance_after_count, legacy_tx_balance_after_col;
  END IF;

  -- Latest tx balance_after should match account balance.
  EXECUTE format($q$
    WITH latest_tx AS (
      SELECT DISTINCT ON (%1$I)
        %1$I AS account_id,
        %2$I AS last_balance_after
      FROM %3$s
      WHERE %2$I IS NOT NULL
      ORDER BY %1$I, %4$I DESC
    )
    SELECT COUNT(*)
    FROM %5$s a
    JOIN latest_tx l ON l.account_id = a.%6$I
    WHERE a.%7$I <> l.last_balance_after
  $q$,
    legacy_tx_account_id_col,
    legacy_tx_balance_after_col,
    tx_reg,
    legacy_tx_created_at_col,
    account_reg,
    legacy_account_id_col,
    legacy_account_balance_col
  ) INTO mismatch_count;

  IF mismatch_count > 0 THEN
    RAISE EXCEPTION 'Legacy check failed: % accounts mismatch latest tx balance-after', mismatch_count;
  END IF;
END $$;

SELECT 'CryptoOrder read-only checks passed (I5 + Legacy consistency)' AS result;
