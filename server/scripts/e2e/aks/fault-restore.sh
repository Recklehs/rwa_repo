#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

NAMESPACE="${E2E_AKS_NAMESPACE:-rwa}"
DEPLOYMENT="${E2E_AKS_RWA_DEPLOYMENT:-rwa-server}"
SNAPSHOT_FILE=""
AZ_DIR="${AZURE_CONFIG_DIR:-/tmp/azure}"

usage() {
  cat <<USAGE
Usage: $(basename "$0") --snapshot-file <path> [options]

Options:
  --namespace <ns>
  --deployment <name>
  --snapshot-file <path>
  --az-config-dir <path>
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
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

if [[ -z "$SNAPSHOT_FILE" || ! -f "$SNAPSHOT_FILE" ]]; then
  log_error "snapshot file not found: $SNAPSHOT_FILE"
  exit 1
fi

require_cmd kubectl >/dev/null
require_cmd jq >/dev/null

rpc_value="$(jq -r '.spec.template.spec.containers[0].env[]? | select(.name=="GIWA_RPC_URL") | .value // empty' "$SNAPSHOT_FILE")"
kafka_value="$(jq -r '.spec.template.spec.containers[0].env[]? | select(.name=="KAFKA_BOOTSTRAP_SERVERS") | .value // empty' "$SNAPSHOT_FILE")"
replicas="$(jq -r '.spec.replicas // 1' "$SNAPSHOT_FILE")"

if [[ -n "$rpc_value" ]]; then
  AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" set env "deployment/$DEPLOYMENT" GIWA_RPC_URL="$rpc_value"
else
  AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" set env "deployment/$DEPLOYMENT" GIWA_RPC_URL-
fi

if [[ -n "$kafka_value" ]]; then
  AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" set env "deployment/$DEPLOYMENT" KAFKA_BOOTSTRAP_SERVERS="$kafka_value"
else
  AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" set env "deployment/$DEPLOYMENT" KAFKA_BOOTSTRAP_SERVERS-
fi

AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" scale "deployment/$DEPLOYMENT" --replicas="$replicas"
AZURE_CONFIG_DIR="$AZ_DIR" kubectl -n "$NAMESPACE" rollout status "deployment/$DEPLOYMENT" --timeout=180s

log_info "fault restored from snapshot: $SNAPSHOT_FILE"
