-- Example variable mapping for cryptoorder_catalog_checks.sql
--
-- Usage:
-- psql ... \
--   -v refresh_table=public.refresh_tokens \
--   -v refresh_hash_column=token_hash \
--   -v refresh_plain_column=token_plain \
--   -v legacy_account_table=public.krw_accounts \
--   -v legacy_account_id_column=account_id \
--   -v legacy_account_balance_column=balance \
--   -v legacy_tx_table=public.krw_transactions \
--   -v legacy_tx_account_id_column=account_id \
--   -v legacy_tx_balance_after_column=balance_after_transaction \
--   -v legacy_tx_created_at_column=created_at \
--   -f server/scripts/e2e/sql/cryptoorder/cryptoorder_catalog_checks.sql

SELECT 'Provide real table/column mappings via -v options (see comments in this file).' AS note;
