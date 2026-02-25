#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

MODE="all"
NAMESPACE="${E2E_AKS_NAMESPACE:-rwa}"
DEPLOYMENT="${E2E_AKS_RWA_DEPLOYMENT:-rwa-server}"
SNAPSHOT_FILE=""
AZ_DIR="${AZURE_CONFIG_DIR:-/tmp/azure}"
INVALID_RPC_URL="${E2E_INVALID_RPC_URL:-http://127.0.0.1:1}"
INVALID_KAFKA_BOOTSTRAP="${E2E_INVALID_KAFKA_BOOTSTRAP:-invalid-broker:9092}"

usage() {
  cat <<USAGE
Usage: $(basename "$0") --snapshot-file <path> [options]

Options:
  --mode <rpc|kafka|all>
  --namespace <ns>
  --deployment <name>
  --snapshot-file <path>
  --az-config-dir <path>
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      MODE="${2:-all}"
      shift 2
      ;;
    --namespace)
      NAMESPACE="${2:-$NAMESPACE}"
      shift 2
      ;;
    --deployment)
      DEPLOYMENT="${2:-$DEPLOYMENT}"
      shift 2
      ;;
    --snapshot-file)
      SNAPSHOT_FILE="${2:-}"
      shift 2
      ;;
    --az-config-dir)
      AZ_DIR="${2:-$AZ_DIR}"
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

if [[ -z "$SNAPSHOT_FILE" ]]; then
  usage
  exit 1
fi

require_cmd kubectl >/dev/null
require_cmd jq >/dev/null

mkdir -p "$(dirname "$SNAPSHOT_FILE")"

AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" get deployment "$DEPLOYMENT" -o json > "$SNAPSHOT_FILE"
log_info "saved deployment snapshot: $SNAPSHOT_FILE"

case "$MODE" in
  rpc)
    AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" set env "deployment/$DEPLOYMENT" GIWA_RPC_URL="$INVALID_RPC_URL"
    ;;
  kafka)
    AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" set env "deployment/$DEPLOYMENT" KAFKA_BOOTSTRAP_SERVERS="$INVALID_KAFKA_BOOTSTRAP"
    ;;
  all)
    AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" set env "deployment/$DEPLOYMENT" GIWA_RPC_URL="$INVALID_RPC_URL"
    AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" set env "deployment/$DEPLOYMENT" KAFKA_BOOTSTRAP_SERVERS="$INVALID_KAFKA_BOOTSTRAP"
    ;;
  *)
    log_error "unsupported mode: $MODE"
    exit 1
    ;;
esac

AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" rollout status "deployment/$DEPLOYMENT" --timeout=180s
log_info "fault injected mode=$MODE deployment=$DEPLOYMENT"
