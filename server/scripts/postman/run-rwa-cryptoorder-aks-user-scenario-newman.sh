#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLLECTION="$SCRIPT_DIR/rwa-cryptoorder-aks-user-scenario.postman_collection.json"
ENVIRONMENT="$SCRIPT_DIR/rwa-cryptoorder-aks-user-scenario.postman_environment.json"
RESULT_DIR="$SCRIPT_DIR/results"

ADMIN_TOKEN="${ADMIN_TOKEN:-}"
DELAY_MS="${NEWMAN_DELAY_MS:-1000}"
RWA_BASE_URL="${RWA_BASE_URL:-}"
CRYPTOORDER_BASE_URL="${CRYPTOORDER_BASE_URL:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --admin-token)
      ADMIN_TOKEN="$2"
      shift 2
      ;;
    --delay-ms)
      DELAY_MS="$2"
      shift 2
      ;;
    --rwa-base-url)
      RWA_BASE_URL="$2"
      shift 2
      ;;
    --cryptoorder-base-url)
      CRYPTOORDER_BASE_URL="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: $0 [--admin-token <token>] [--delay-ms <ms>] [--rwa-base-url <url>] [--cryptoorder-base-url <url>]" >&2
      exit 1
      ;;
  esac
done

if ! command -v newman >/dev/null 2>&1; then
  echo "newman is not installed."
  echo "Install: npm i -g newman"
  exit 1
fi

if [[ -z "$ADMIN_TOKEN" ]]; then
  echo "ADMIN_TOKEN is required."
  echo "Use env var: ADMIN_TOKEN=... $0"
  echo "or option:   $0 --admin-token <token>"
  exit 1
fi

mkdir -p "$RESULT_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
JSON_REPORT="$RESULT_DIR/rwa-cryptoorder-aks-user-scenario-$TS.json"

CMD=(
  newman run "$COLLECTION"
  -e "$ENVIRONMENT"
  --env-var "admin_token=$ADMIN_TOKEN"
  --delay-request "$DELAY_MS"
  --reporters cli,json
  --reporter-json-export "$JSON_REPORT"
)

if [[ -n "$RWA_BASE_URL" ]]; then
  CMD+=(--env-var "rwa_base_url=$RWA_BASE_URL")
fi

if [[ -n "$CRYPTOORDER_BASE_URL" ]]; then
  CMD+=(--env-var "cryptoorder_base_url=$CRYPTOORDER_BASE_URL")
fi

"${CMD[@]}"

echo
printf 'JSON report: %s\n' "$JSON_REPORT"
