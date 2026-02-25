#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ENV_FILE=""
OUT_FILE="$SCRIPT_DIR/env/cryptoorder-sql.auto.env"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --env-file <path>     Load env before DB introspection
  --out-file <path>     Output env file (default: server/scripts/e2e/env/cryptoorder-sql.auto.env)
  -h, --help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file)
      ENV_FILE="${2:-}"
      shift 2
      ;;
    --out-file)
      OUT_FILE="${2:-$OUT_FILE}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[prepare-cryptoorder-sql] unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -n "$ENV_FILE" ]]; then
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "[prepare-cryptoorder-sql] env file not found: $ENV_FILE" >&2
    exit 1
  fi
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "[prepare-cryptoorder-sql] missing env: $name" >&2
    exit 1
  fi
}

require_var E2E_CRYPTOORDER_DB_URL
require_var E2E_CRYPTOORDER_DB_USER
require_var E2E_CRYPTOORDER_DB_PASSWORD

query_first() {
  local sql="$1"
  PGPASSWORD="$E2E_CRYPTOORDER_DB_PASSWORD" \
    psql "$E2E_CRYPTOORDER_DB_URL" \
      -U "$E2E_CRYPTOORDER_DB_USER" \
      -v ON_ERROR_STOP=1 \
      -Atqc "$sql" | head -n1
}

escape_sql_literal() {
  printf "%s" "$1" | sed "s/'/''/g"
}

pick_column() {
  local table_fq="$1"
  local predicate="$2"
  local escaped
  escaped="$(escape_sql_literal "$table_fq")"
  query_first "
    SELECT c.column_name
    FROM information_schema.columns c
    WHERE c.table_schema || '.' || c.table_name = '$escaped'
      AND (${predicate})
    ORDER BY c.ordinal_position
    LIMIT 1
  "
}

refresh_table="$(query_first "
  WITH candidates AS (
    SELECT
      t.table_schema || '.' || t.table_name AS fqtn,
      t.table_name,
      SUM(CASE WHEN lower(c.column_name) IN ('token_hash','refresh_token_hash','tokenhash') THEN 1 ELSE 0 END) AS hash_cols
    FROM information_schema.tables t
    JOIN information_schema.columns c
      ON c.table_schema = t.table_schema
     AND c.table_name = t.table_name
    WHERE t.table_type = 'BASE TABLE'
      AND t.table_schema NOT IN ('pg_catalog', 'information_schema')
      AND (
        lower(t.table_name) LIKE '%refresh%'
        OR lower(c.column_name) IN ('token_hash','refresh_token_hash','tokenhash')
      )
    GROUP BY 1, 2
  )
  SELECT fqtn
  FROM candidates
  ORDER BY
    CASE WHEN lower(table_name) LIKE '%refresh%' THEN 0 ELSE 1 END,
    CASE WHEN hash_cols > 0 THEN 0 ELSE 1 END,
    table_name
  LIMIT 1
")"

refresh_hash_column=""
refresh_plain_column=""
if [[ -n "$refresh_table" ]]; then
  refresh_hash_column="$(pick_column "$refresh_table" "lower(c.column_name) IN ('token_hash','refresh_token_hash','tokenhash','token_hash_value')")"
  refresh_plain_column="$(pick_column "$refresh_table" "lower(c.column_name) IN ('token_plain','refresh_token','token','raw_token','token_value')")"
fi

legacy_account_table="$(query_first "
  WITH candidates AS (
    SELECT
      t.table_schema || '.' || t.table_name AS fqtn,
      t.table_name,
      SUM(CASE WHEN lower(c.column_name) IN ('balance','current_balance','account_balance') THEN 1 ELSE 0 END) AS balance_cols
    FROM information_schema.tables t
    JOIN information_schema.columns c
      ON c.table_schema = t.table_schema
     AND c.table_name = t.table_name
    WHERE t.table_type = 'BASE TABLE'
      AND t.table_schema NOT IN ('pg_catalog', 'information_schema')
      AND (
        lower(t.table_name) LIKE '%account%'
        OR lower(t.table_name) LIKE 'krw_%'
      )
    GROUP BY 1, 2
  )
  SELECT fqtn
  FROM candidates
  ORDER BY
    CASE WHEN lower(table_name) LIKE '%krw%account%' THEN 0 ELSE 1 END,
    CASE WHEN balance_cols > 0 THEN 0 ELSE 1 END,
    table_name
  LIMIT 1
")"

legacy_account_id_column="account_id"
legacy_account_balance_column="balance"
if [[ -n "$legacy_account_table" ]]; then
  acc_id="$(pick_column "$legacy_account_table" "lower(c.column_name) IN ('account_id','id','accountid')")"
  acc_bal="$(pick_column "$legacy_account_table" "lower(c.column_name) IN ('balance','current_balance','account_balance')")"
  [[ -n "$acc_id" ]] && legacy_account_id_column="$acc_id"
  [[ -n "$acc_bal" ]] && legacy_account_balance_column="$acc_bal"
fi

legacy_tx_table="$(query_first "
  WITH candidates AS (
    SELECT
      t.table_schema || '.' || t.table_name AS fqtn,
      t.table_name,
      SUM(CASE WHEN lower(c.column_name) IN ('account_id','accountid') THEN 1 ELSE 0 END) AS account_cols,
      SUM(CASE WHEN lower(c.column_name) IN ('balance_after_transaction','balance_after','after_balance') THEN 1 ELSE 0 END) AS balance_after_cols
    FROM information_schema.tables t
    JOIN information_schema.columns c
      ON c.table_schema = t.table_schema
     AND c.table_name = t.table_name
    WHERE t.table_type = 'BASE TABLE'
      AND t.table_schema NOT IN ('pg_catalog', 'information_schema')
      AND (
        lower(t.table_name) LIKE '%transaction%'
        OR lower(t.table_name) LIKE '%history%'
        OR lower(t.table_name) LIKE '%tx%'
      )
    GROUP BY 1, 2
  )
  SELECT fqtn
  FROM candidates
  ORDER BY
    CASE WHEN lower(table_name) LIKE '%krw%transaction%' THEN 0 ELSE 1 END,
    CASE WHEN account_cols > 0 THEN 0 ELSE 1 END,
    CASE WHEN balance_after_cols > 0 THEN 0 ELSE 1 END,
    table_name
  LIMIT 1
")"

legacy_tx_account_id_column="account_id"
legacy_tx_balance_after_column="balance_after_transaction"
legacy_tx_created_at_column="created_at"
if [[ -n "$legacy_tx_table" ]]; then
  tx_acc_id="$(pick_column "$legacy_tx_table" "lower(c.column_name) IN ('account_id','accountid')")"
  tx_bal_after="$(pick_column "$legacy_tx_table" "lower(c.column_name) IN ('balance_after_transaction','balance_after','after_balance')")"
  tx_created="$(pick_column "$legacy_tx_table" "lower(c.column_name) IN ('created_at','createdat','transaction_at','occurred_at','timestamp')")"
  [[ -n "$tx_acc_id" ]] && legacy_tx_account_id_column="$tx_acc_id"
  [[ -n "$tx_bal_after" ]] && legacy_tx_balance_after_column="$tx_bal_after"
  [[ -n "$tx_created" ]] && legacy_tx_created_at_column="$tx_created"
fi

mkdir -p "$(dirname "$OUT_FILE")"
cat > "$OUT_FILE" <<EOF
# Auto-generated by prepare-cryptoorder-sql.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)
# Review values before use.

E2E_CRYPTOORDER_REFRESH_TABLE=${refresh_table}
E2E_CRYPTOORDER_REFRESH_HASH_COLUMN=${refresh_hash_column}
E2E_CRYPTOORDER_REFRESH_PLAIN_COLUMN=${refresh_plain_column}

E2E_CRYPTOORDER_LEGACY_ACCOUNT_TABLE=${legacy_account_table}
E2E_CRYPTOORDER_LEGACY_ACCOUNT_ID_COLUMN=${legacy_account_id_column}
E2E_CRYPTOORDER_LEGACY_ACCOUNT_BALANCE_COLUMN=${legacy_account_balance_column}
E2E_CRYPTOORDER_LEGACY_TX_TABLE=${legacy_tx_table}
E2E_CRYPTOORDER_LEGACY_TX_ACCOUNT_ID_COLUMN=${legacy_tx_account_id_column}
E2E_CRYPTOORDER_LEGACY_TX_BALANCE_AFTER_COLUMN=${legacy_tx_balance_after_column}
E2E_CRYPTOORDER_LEGACY_TX_CREATED_AT_COLUMN=${legacy_tx_created_at_column}
EOF

echo "[prepare-cryptoorder-sql] wrote: $OUT_FILE"

missing=0
for key in \
  E2E_CRYPTOORDER_REFRESH_TABLE \
  E2E_CRYPTOORDER_REFRESH_HASH_COLUMN \
  E2E_CRYPTOORDER_LEGACY_ACCOUNT_TABLE \
  E2E_CRYPTOORDER_LEGACY_TX_TABLE; do
  value="$(grep "^${key}=" "$OUT_FILE" | head -n1 | cut -d= -f2-)"
  if [[ -z "$value" ]]; then
    echo "[prepare-cryptoorder-sql] WARN: ${key} could not be auto-detected." >&2
    missing=1
  fi
done

if [[ $missing -eq 1 ]]; then
  echo "[prepare-cryptoorder-sql] review and fill missing entries manually." >&2
fi

echo "[prepare-cryptoorder-sql] next:"
echo "  1) source $OUT_FILE"
echo "  2) run run-catalog.sh with --cryptoorder-sql $SCRIPT_DIR/sql/cryptoorder/cryptoorder_catalog_checks.sql"
