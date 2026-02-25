#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

SCENARIO=""
RUN_DIR=""
RUN_ID=""
REQUEST_PREFIX=""
AGGREGATE_USER_ID=""
CRYPTOORDER_SQL=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") --scenario <name> --run-dir <path> [options]

Scenarios:
  I1_CUSTODY
  I5_CRYPTOORDER
  B_LEGACY_CRYPTOORDER
  B4_CUSTODY
  T3_CUSTODY
  T4_CUSTODY
  T5_FAIL_CUSTODY
  T5_SUCCESS_CUSTODY

Options:
  --run-id <id>
  --request-prefix <prefix>
  --aggregate-user-id <uuid>
  --cryptoorder-sql <path>
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario)
      SCENARIO="${2:-}"
      shift 2
      ;;
    --run-dir)
      RUN_DIR="${2:-}"
      shift 2
      ;;
    --run-id)
      RUN_ID="${2:-}"
      shift 2
      ;;
    --request-prefix)
      REQUEST_PREFIX="${2:-}"
      shift 2
      ;;
    --aggregate-user-id)
      AGGREGATE_USER_ID="${2:-}"
      shift 2
      ;;
    --cryptoorder-sql)
      CRYPTOORDER_SQL="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      log_error "unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$SCENARIO" || -z "$RUN_DIR" ]]; then
  usage
  exit 1
fi

mkdir -p "$RUN_DIR/raw"
log_file="$RUN_DIR/raw/db-${SCENARIO}.log"

run_psql() {
  local url="$1"
  local user="$2"
  local password="$3"
  local sql_file="$4"
  shift 4

  if [[ ! -f "$sql_file" ]]; then
    log_error "sql file not found: $sql_file"
    return 1
  fi

  PGPASSWORD="$password" psql "$url" -U "$user" -v ON_ERROR_STOP=1 -f "$sql_file" "$@" >"$log_file" 2>&1
}

cryptoorder_var_args() {
  local args=()

  [[ -n "${E2E_CRYPTOORDER_REFRESH_TABLE:-}" ]] && args+=(-v "refresh_table=${E2E_CRYPTOORDER_REFRESH_TABLE}")
  [[ -n "${E2E_CRYPTOORDER_REFRESH_HASH_COLUMN:-}" ]] && args+=(-v "refresh_hash_column=${E2E_CRYPTOORDER_REFRESH_HASH_COLUMN}")
  [[ -n "${E2E_CRYPTOORDER_REFRESH_PLAIN_COLUMN:-}" ]] && args+=(-v "refresh_plain_column=${E2E_CRYPTOORDER_REFRESH_PLAIN_COLUMN}")

  [[ -n "${E2E_CRYPTOORDER_LEGACY_ACCOUNT_TABLE:-}" ]] && args+=(-v "legacy_account_table=${E2E_CRYPTOORDER_LEGACY_ACCOUNT_TABLE}")
  [[ -n "${E2E_CRYPTOORDER_LEGACY_ACCOUNT_ID_COLUMN:-}" ]] && args+=(-v "legacy_account_id_column=${E2E_CRYPTOORDER_LEGACY_ACCOUNT_ID_COLUMN}")
  [[ -n "${E2E_CRYPTOORDER_LEGACY_ACCOUNT_BALANCE_COLUMN:-}" ]] && args+=(-v "legacy_account_balance_column=${E2E_CRYPTOORDER_LEGACY_ACCOUNT_BALANCE_COLUMN}")
  [[ -n "${E2E_CRYPTOORDER_LEGACY_TX_TABLE:-}" ]] && args+=(-v "legacy_tx_table=${E2E_CRYPTOORDER_LEGACY_TX_TABLE}")
  [[ -n "${E2E_CRYPTOORDER_LEGACY_TX_ACCOUNT_ID_COLUMN:-}" ]] && args+=(-v "legacy_tx_account_id_column=${E2E_CRYPTOORDER_LEGACY_TX_ACCOUNT_ID_COLUMN}")
  [[ -n "${E2E_CRYPTOORDER_LEGACY_TX_BALANCE_AFTER_COLUMN:-}" ]] && args+=(-v "legacy_tx_balance_after_column=${E2E_CRYPTOORDER_LEGACY_TX_BALANCE_AFTER_COLUMN}")
  [[ -n "${E2E_CRYPTOORDER_LEGACY_TX_CREATED_AT_COLUMN:-}" ]] && args+=(-v "legacy_tx_created_at_column=${E2E_CRYPTOORDER_LEGACY_TX_CREATED_AT_COLUMN}")

  printf '%s\n' "${args[@]}"
}

require_db_env() {
  local prefix="$1"
  local url_var="${prefix}_DB_URL"
  local user_var="${prefix}_DB_USER"
  local pass_var="${prefix}_DB_PASSWORD"

  if [[ -z "${!url_var:-}" || -z "${!user_var:-}" || -z "${!pass_var:-}" ]]; then
    log_error "missing DB env for prefix=$prefix"
    return 1
  fi
}

case "$SCENARIO" in
  I1_CUSTODY)
    require_db_env E2E_CUSTODY
    run_psql "$E2E_CUSTODY_DB_URL" "$E2E_CUSTODY_DB_USER" "$E2E_CUSTODY_DB_PASSWORD" \
      "$SCRIPT_DIR/sql/custody/i1_no_duplicates.sql" \
      -v "run_id=${RUN_ID}"
    ;;
  I5_CRYPTOORDER)
    require_db_env E2E_CRYPTOORDER
    if [[ -z "$CRYPTOORDER_SQL" ]]; then
      log_error "I5 requires --cryptoorder-sql"
      exit 2
    fi
    mapfile -t CO_ARGS < <(cryptoorder_var_args)
    run_psql "$E2E_CRYPTOORDER_DB_URL" "$E2E_CRYPTOORDER_DB_USER" "$E2E_CRYPTOORDER_DB_PASSWORD" \
      "$CRYPTOORDER_SQL" \
      -v "run_id=${RUN_ID}" \
      "${CO_ARGS[@]}"
    ;;
  B_LEGACY_CRYPTOORDER)
    require_db_env E2E_CRYPTOORDER
    if [[ -z "$CRYPTOORDER_SQL" ]]; then
      log_error "B_LEGACY_CRYPTOORDER requires --cryptoorder-sql"
      exit 2
    fi
    mapfile -t CO_ARGS < <(cryptoorder_var_args)
    run_psql "$E2E_CRYPTOORDER_DB_URL" "$E2E_CRYPTOORDER_DB_USER" "$E2E_CRYPTOORDER_DB_PASSWORD" \
      "$CRYPTOORDER_SQL" \
      -v "run_id=${RUN_ID}" \
      "${CO_ARGS[@]}"
    ;;
  B4_CUSTODY)
    require_db_env E2E_CUSTODY
    run_psql "$E2E_CUSTODY_DB_URL" "$E2E_CUSTODY_DB_USER" "$E2E_CUSTODY_DB_PASSWORD" \
      "$SCRIPT_DIR/sql/custody/b4_no_negative_balances.sql" \
      -v "run_id=${RUN_ID}"
    ;;
  T3_CUSTODY)
    require_db_env E2E_CUSTODY
    run_psql "$E2E_CUSTODY_DB_URL" "$E2E_CUSTODY_DB_USER" "$E2E_CUSTODY_DB_PASSWORD" \
      "$SCRIPT_DIR/sql/custody/t3_outbox_state_machine.sql" \
      -v "run_id=${RUN_ID}"
    ;;
  T4_CUSTODY)
    require_db_env E2E_CUSTODY
    run_psql "$E2E_CUSTODY_DB_URL" "$E2E_CUSTODY_DB_USER" "$E2E_CUSTODY_DB_PASSWORD" \
      "$SCRIPT_DIR/sql/custody/t4_failed_outbox_for_request.sql" \
      -v "run_id=${RUN_ID}" \
      -v "request_prefix=${REQUEST_PREFIX}"
    ;;
  T5_FAIL_CUSTODY)
    require_db_env E2E_CUSTODY
    run_psql "$E2E_CUSTODY_DB_URL" "$E2E_CUSTODY_DB_USER" "$E2E_CUSTODY_DB_PASSWORD" \
      "$SCRIPT_DIR/sql/custody/t5_send_fail_for_user.sql" \
      -v "run_id=${RUN_ID}" \
      -v "aggregate_user_id=${AGGREGATE_USER_ID}"
    ;;
  T5_SUCCESS_CUSTODY)
    require_db_env E2E_CUSTODY
    run_psql "$E2E_CUSTODY_DB_URL" "$E2E_CUSTODY_DB_USER" "$E2E_CUSTODY_DB_PASSWORD" \
      "$SCRIPT_DIR/sql/custody/t5_send_success_for_user.sql" \
      -v "run_id=${RUN_ID}" \
      -v "aggregate_user_id=${AGGREGATE_USER_ID}"
    ;;
  *)
    log_error "unsupported DB scenario: $SCENARIO"
    exit 1
    ;;
esac

log_info "db check passed: $SCENARIO"
