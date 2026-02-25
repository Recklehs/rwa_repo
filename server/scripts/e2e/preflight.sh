#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$SCRIPT_DIR/bin:$PATH"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

ENV_FILE=""
RUN_DIR=""
CHECK_NEWMAN="false"
CHECK_K6="false"
CHECK_KUBECTL="false"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --env-file <path>        Load env file before checks
  --run-dir <path>         Run output directory (writes raw/preflight.json)
  --check-newman <bool>    Validate newman command (default: false)
  --check-k6 <bool>        Validate k6 command (default: false)
  --check-kubectl <bool>   Validate kubectl auth to AKS (default: false)
  -h, --help               Show help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file)
      ENV_FILE="${2:-}"
      shift 2
      ;;
    --run-dir)
      RUN_DIR="${2:-}"
      shift 2
      ;;
    --check-newman)
      CHECK_NEWMAN="${2:-false}"
      shift 2
      ;;
    --check-k6)
      CHECK_K6="${2:-false}"
      shift 2
      ;;
    --check-kubectl)
      CHECK_KUBECTL="${2:-false}"
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

if [[ -n "$ENV_FILE" ]]; then
  if [[ ! -f "$ENV_FILE" ]]; then
    log_error "env file not found: $ENV_FILE"
    exit 1
  fi
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

errors=()
add_error() {
  errors+=("$1")
}

require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    add_error "missing env: $name"
  fi
}

check_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    add_error "missing command: $cmd"
  fi
}

check_health() {
  local name="$1"
  local base_url="$2"
  local body
  if ! body="$(curl -fsS "$base_url/actuator/health" 2>/dev/null)"; then
    add_error "$name health check failed: $base_url/actuator/health"
    return
  fi

  if ! echo "$body" | jq -e '.status == "UP"' >/dev/null 2>&1; then
    add_error "$name health status is not UP"
  fi
}

check_db() {
  local name="$1"
  local url="$2"
  local user="$3"
  local password="$4"

  if ! PGPASSWORD="$password" psql "$url" -U "$user" -v ON_ERROR_STOP=1 -Atqc 'select 1' >/dev/null 2>&1; then
    add_error "$name DB connection failed"
  fi
}

check_kubectl_auth() {
  local ns="${E2E_AKS_NAMESPACE:-rwa}"
  local az_dir="${AZURE_CONFIG_DIR:-/tmp/azure}"
  if ! AZURE_CONFIG_DIR="$az_dir" kubectl auth can-i get deployment -n "$ns" >/dev/null 2>&1; then
    add_error "kubectl/az auth failed for namespace=$ns (run az login)"
  fi
}

# Mandatory baseline checks.
check_command curl
check_command jq
check_command psql

if [[ "$CHECK_NEWMAN" == "true" ]]; then
  check_command newman
fi
if [[ "$CHECK_K6" == "true" ]]; then
  check_command k6
fi
if [[ "$CHECK_KUBECTL" == "true" ]]; then
  check_command kubectl
fi

require_var E2E_CRYPTOORDER_BASE_URL
require_var E2E_RWA_BASE_URL
require_var E2E_ADMIN_TOKEN
require_var E2E_SERVICE_TOKEN
require_var E2E_CUSTODY_DB_URL
require_var E2E_CUSTODY_DB_USER
require_var E2E_CUSTODY_DB_PASSWORD
require_var E2E_CRYPTOORDER_DB_URL
require_var E2E_CRYPTOORDER_DB_USER
require_var E2E_CRYPTOORDER_DB_PASSWORD

if [[ ${#errors[@]} -eq 0 ]]; then
  check_health "cryptoorder" "$E2E_CRYPTOORDER_BASE_URL"
  check_health "rwa" "$E2E_RWA_BASE_URL"
  check_db "custody" "$E2E_CUSTODY_DB_URL" "$E2E_CUSTODY_DB_USER" "$E2E_CUSTODY_DB_PASSWORD"
  check_db "cryptoorder" "$E2E_CRYPTOORDER_DB_URL" "$E2E_CRYPTOORDER_DB_USER" "$E2E_CRYPTOORDER_DB_PASSWORD"
  if [[ "$CHECK_KUBECTL" == "true" ]]; then
    check_kubectl_auth
  fi
fi

status="PASS"
if [[ ${#errors[@]} -gt 0 ]]; then
  status="FAIL"
fi

if [[ -n "$RUN_DIR" ]]; then
  mkdir -p "$RUN_DIR/raw"
  errors_json='[]'
  if [[ ${#errors[@]} -gt 0 ]]; then
    errors_json="$(printf '%s\n' "${errors[@]}" | jq -R . | jq -s .)"
  fi
  jq -nc \
    --arg status "$status" \
    --argjson checkNewman "$(json_bool "$CHECK_NEWMAN")" \
    --argjson checkK6 "$(json_bool "$CHECK_K6")" \
    --argjson checkKubectl "$(json_bool "$CHECK_KUBECTL")" \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson errors "$errors_json" \
    '{status:$status,checkedAt:$ts,checks:{newman:$checkNewman,k6:$checkK6,kubectl:$checkKubectl},errors:$errors}' \
    > "$RUN_DIR/raw/preflight.json"
fi

if [[ "$status" == "FAIL" ]]; then
  log_error "preflight failed"
  printf ' - %s\n' "${errors[@]}" >&2
  exit 1
fi

log_info "preflight passed"
