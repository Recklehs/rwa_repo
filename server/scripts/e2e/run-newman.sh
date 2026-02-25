#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$SCRIPT_DIR/bin:$PATH"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

SUITE=""
RUN_DIR=""
DELAY_MS="${E2E_NEWMAN_DELAY_MS:-750}"

usage() {
  cat <<USAGE
Usage: $(basename "$0") --suite <name> --run-dir <path>

Suites:
  smoke | idempotency | security | legacy | ops
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite)
      SUITE="${2:-}"
      shift 2
      ;;
    --run-dir)
      RUN_DIR="${2:-}"
      shift 2
      ;;
    --delay-ms)
      DELAY_MS="${2:-750}"
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

if [[ -z "$SUITE" || -z "$RUN_DIR" ]]; then
  usage
  exit 1
fi

require_cmd newman >/dev/null

mkdir -p "$RUN_DIR/raw"

run_collection() {
  local label="$1"
  local collection="$2"
  local env_file="$3"
  local out_json="$RUN_DIR/raw/newman-${SUITE}-${label}.json"

  if [[ ! -f "$collection" ]]; then
    log_error "collection not found: $collection"
    return 1
  fi
  if [[ ! -f "$env_file" ]]; then
    log_error "environment not found: $env_file"
    return 1
  fi

  log_info "newman run suite=$SUITE label=$label"
  newman run "$collection" \
    -e "$env_file" \
    --delay-request "$DELAY_MS" \
    --reporters cli,json \
    --reporter-json-export "$out_json" \
    --env-var "cryptoorder_base_url=${E2E_CRYPTOORDER_BASE_URL}" \
    --env-var "rwa_base_url=${E2E_RWA_BASE_URL}" \
    --env-var "custodyBaseUrl=${E2E_RWA_BASE_URL}" \
    --env-var "cryptoOrderBaseUrl=${E2E_CRYPTOORDER_BASE_URL}" \
    --env-var "admin_token=${E2E_ADMIN_TOKEN}" \
    --env-var "adminToken=${E2E_ADMIN_TOKEN}" \
    --env-var "service_token=${E2E_SERVICE_TOKEN}" \
    --env-var "serviceToken=${E2E_SERVICE_TOKEN}" \
    --env-var "provider=${E2E_PROVIDER:-MEMBER}" \
    --env-var "expect_jwks_mode=${E2E_EXPECT_JWKS_MODE:-true}"
}

POSTMAN_DIR="$SCRIPT_DIR/postman"

case "$SUITE" in
  smoke)
    run_collection "main" \
      "$POSTMAN_DIR/rwa-cryptoorder-aks-user-scenario.postman_collection.json" \
      "$POSTMAN_DIR/rwa-cryptoorder-aks-user-scenario.postman_environment.json"
    ;;
  idempotency)
    run_collection "k8s-check" \
      "$POSTMAN_DIR/rwa-cryptoorder-k8s-deploy-check.postman_collection.json" \
      "$POSTMAN_DIR/rwa-cryptoorder-k8s-deploy-check.postman_environment.example.json"

    run_collection "integration" \
      "$POSTMAN_DIR/cryptoorder-custody-integration.postman_collection.json" \
      "$POSTMAN_DIR/cryptoorder-custody-local.postman_environment.json"
    ;;
  security)
    run_collection "boundary" \
      "$POSTMAN_DIR/e2e-security-boundary.postman_collection.json" \
      "$POSTMAN_DIR/e2e-catalog.postman_environment.json"
    ;;
  legacy)
    run_collection "legacy" \
      "$POSTMAN_DIR/e2e-legacy.postman_collection.json" \
      "$POSTMAN_DIR/e2e-catalog.postman_environment.json"
    ;;
  ops)
    run_collection "ops" \
      "$POSTMAN_DIR/e2e-ops.postman_collection.json" \
      "$POSTMAN_DIR/e2e-catalog.postman_environment.json"
    ;;
  *)
    log_error "unsupported suite for newman: $SUITE"
    exit 1
    ;;
esac

log_info "newman suite completed: $SUITE"
