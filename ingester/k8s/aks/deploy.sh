#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="${NAMESPACE:-rwa}"
APP_NAME="${APP_NAME:-rwa-ingester}"
CONFIGMAP_NAME="${CONFIGMAP_NAME:-rwa-ingester-config}"
SECRET_NAME="${SECRET_NAME:-rwa-ingester-secret}"
IMAGE="${IMAGE:-}"

if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: kubectl is required." >&2
  exit 1
fi

if [[ -z "${IMAGE}" ]]; then
  echo "ERROR: IMAGE is required." >&2
  echo "Example: IMAGE=myacr.azurecr.io/rwa-ingester:20260225-1500-a1b2c3d ./deploy.sh" >&2
  exit 1
fi

if [[ "${IMAGE}" =~ :latest$ ]]; then
  echo "ERROR: latest tag is not allowed. Use an immutable tag (e.g. YYYYMMDD-HHMM-gitsha)." >&2
  exit 1
fi

kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1 || kubectl create namespace "${NAMESPACE}"

sed \
  -e "s|__APP_NAME__|${APP_NAME}|g" \
  -e "s|__CONFIGMAP_NAME__|${CONFIGMAP_NAME}|g" \
  -e "s|__SECRET_NAME__|${SECRET_NAME}|g" \
  -e "s|__IMAGE__|${IMAGE}|g" \
  "${SCRIPT_DIR}/deployment.yaml" | kubectl -n "${NAMESPACE}" apply -f -

kubectl -n "${NAMESPACE}" rollout status deployment/"${APP_NAME}" --timeout=180s
kubectl -n "${NAMESPACE}" get pods -l app="${APP_NAME}" -o wide
