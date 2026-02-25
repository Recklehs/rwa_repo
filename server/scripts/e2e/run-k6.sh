#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$SCRIPT_DIR/bin:$PATH"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

SCENARIO=""
RUN_DIR=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") --scenario <I3|T1|T2|B4> --run-dir <path>
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

require_cmd k6 >/dev/null
mkdir -p "$RUN_DIR/raw"

script=""
case "$SCENARIO" in
  I3)
    script="$SCRIPT_DIR/k6/i3_same_key_in_progress.js"
    ;;
  T1)
    script="$SCRIPT_DIR/k6/t1_nonce_same_address.js"
    ;;
  T2)
    script="$SCRIPT_DIR/k6/t2_parallel_diff_address.js"
    ;;
  B4)
    script="$SCRIPT_DIR/k6/b4_concurrent_withdraw.js"
    ;;
  *)
    log_error "unsupported k6 scenario: $SCENARIO"
    exit 1
    ;;
esac

if [[ ! -f "$script" ]]; then
  log_error "k6 script not found: $script"
  exit 1
fi

out_json="$RUN_DIR/raw/k6-${SCENARIO}.json"

log_info "k6 run scenario=$SCENARIO"

k6 run "$script" \
  --summary-export "$out_json" \
  -e RUN_ID="${E2E_RUN_ID:-}" \
  -e RWA_BASE_URL="${E2E_RWA_BASE_URL:-}" \
  -e CRYPTOORDER_BASE_URL="${E2E_CRYPTOORDER_BASE_URL:-}" \
  -e SERVICE_TOKEN="${E2E_SERVICE_TOKEN:-}" \
  -e ADMIN_TOKEN="${E2E_ADMIN_TOKEN:-}" \
  -e I3_USER_ID="${E2E_I3_USER_ID:-}" \
  -e T1_BEARER_TOKEN="${E2E_T1_BEARER_TOKEN:-}" \
  -e T1_UNIT_ID="${E2E_T1_UNIT_ID:-}" \
  -e T1_AMOUNT_RAW="${E2E_T1_AMOUNT_RAW:-1000000000000}" \
  -e T1_UNIT_PRICE_RAW="${E2E_T1_UNIT_PRICE_RAW:-100000000}" \
  -e T2_BEARER_TOKEN_A="${E2E_T2_BEARER_TOKEN_A:-}" \
  -e T2_BEARER_TOKEN_B="${E2E_T2_BEARER_TOKEN_B:-}" \
  -e T2_UNIT_ID_A="${E2E_T2_UNIT_ID_A:-}" \
  -e T2_UNIT_ID_B="${E2E_T2_UNIT_ID_B:-}" \
  -e B4_ACCOUNT_ID="${E2E_B4_ACCOUNT_ID:-}" \
  -e B4_BEARER_TOKEN="${E2E_B4_BEARER_TOKEN:-}" \
  -e B4_WITHDRAW_AMOUNT="${E2E_B4_WITHDRAW_AMOUNT:-1000}"

log_info "k6 scenario completed: $SCENARIO"
