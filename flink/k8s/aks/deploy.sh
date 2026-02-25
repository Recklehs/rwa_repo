#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="${NAMESPACE:-rwa}"
APP_NAME="${APP_NAME:-rwa-flink-indexer}"
CONFIGMAP_NAME="${CONFIGMAP_NAME:-rwa-flink-config}"
SECRET_NAME="${SECRET_NAME:-rwa-flink-secret}"
PVC_NAME="${PVC_NAME:-rwa-flink-state}"
SERVICE_ACCOUNT="${SERVICE_ACCOUNT:-rwa-flink}"
IMAGE="${IMAGE:-}"

if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: kubectl is required." >&2
  exit 1
fi

if [[ -z "${IMAGE}" ]]; then
  echo "ERROR: IMAGE is required." >&2
  echo "Example: IMAGE=2dtteam4temp.azurecr.io/rwa-flink:20260225-1600-abcd123 ./deploy.sh" >&2
  exit 1
fi

if [[ "${IMAGE}" =~ :latest$ ]]; then
  echo "ERROR: latest tag is not allowed. Use an immutable image tag." >&2
  exit 1
fi

if ! kubectl get crd flinkdeployments.flink.apache.org >/dev/null 2>&1; then
  echo "ERROR: Flink Operator CRD not found (flinkdeployments.flink.apache.org)." >&2
  echo "Install Flink Operator first." >&2
  exit 1
fi

kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1 || kubectl create namespace "${NAMESPACE}"

if ! kubectl -n "${NAMESPACE}" get configmap "${CONFIGMAP_NAME}" >/dev/null 2>&1; then
  echo "ERROR: missing ConfigMap ${NAMESPACE}/${CONFIGMAP_NAME}. Run create-env-secret.sh first." >&2
  exit 1
fi

if ! kubectl -n "${NAMESPACE}" get secret "${SECRET_NAME}" >/dev/null 2>&1; then
  echo "ERROR: missing Secret ${NAMESPACE}/${SECRET_NAME}. Run create-env-secret.sh first." >&2
  exit 1
fi

sed \
  -e "s|__APP_NAME__|${APP_NAME}|g" \
  -e "s|__SERVICE_ACCOUNT__|${SERVICE_ACCOUNT}|g" \
  "${SCRIPT_DIR}/rbac.yaml" | kubectl -n "${NAMESPACE}" apply -f -

sed \
  -e "s|__PVC_NAME__|${PVC_NAME}|g" \
  "${SCRIPT_DIR}/pvc.yaml" | kubectl -n "${NAMESPACE}" apply -f -

sed \
  -e "s|__APP_NAME__|${APP_NAME}|g" \
  -e "s|__IMAGE__|${IMAGE}|g" \
  -e "s|__CONFIGMAP_NAME__|${CONFIGMAP_NAME}|g" \
  -e "s|__SECRET_NAME__|${SECRET_NAME}|g" \
  -e "s|__PVC_NAME__|${PVC_NAME}|g" \
  -e "s|__SERVICE_ACCOUNT__|${SERVICE_ACCOUNT}|g" \
  "${SCRIPT_DIR}/flinkdeployment.yaml" | kubectl -n "${NAMESPACE}" apply -f -

max_attempts=60
attempt=1
while (( attempt <= max_attempts )); do
  jm_status="$(kubectl -n "${NAMESPACE}" get flinkdeployment "${APP_NAME}" -o jsonpath='{.status.jobManagerDeploymentStatus}' 2>/dev/null || true)"
  job_state="$(kubectl -n "${NAMESPACE}" get flinkdeployment "${APP_NAME}" -o jsonpath='{.status.jobStatus.state}' 2>/dev/null || true)"

  if [[ "${jm_status}" == "READY" && "${job_state}" == "RUNNING" ]]; then
    echo "FlinkDeployment is ready and running: ${NAMESPACE}/${APP_NAME}"
    kubectl -n "${NAMESPACE}" get flinkdeployment "${APP_NAME}" -o wide
    kubectl -n "${NAMESPACE}" get pods -l app="${APP_NAME}" -o wide || true
    exit 0
  fi

  echo "[wait ${attempt}/${max_attempts}] jobManagerDeploymentStatus=${jm_status:-<empty>} jobState=${job_state:-<empty>}"
  sleep 5
  attempt=$((attempt + 1))
done

echo "ERROR: FlinkDeployment did not reach RUNNING in time." >&2
kubectl -n "${NAMESPACE}" get flinkdeployment "${APP_NAME}" -o yaml | sed -n '1,240p' >&2 || true
kubectl -n "${NAMESPACE}" get pods -o wide >&2 || true
kubectl -n "${NAMESPACE}" get events --sort-by=.lastTimestamp | tail -n 40 >&2 || true
exit 1
