#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-${SCRIPT_DIR}/../../.env}"
NAMESPACE="${NAMESPACE:-rwa}"
CONFIGMAP_NAME="${CONFIGMAP_NAME:-rwa-server-config}"
SECRET_NAME="${SECRET_NAME:-rwa-server-secret}"
SECRET_KEYS="${SECRET_KEYS:-DB_PASSWORD,GAS_STATION_PRIVATE_KEY,ADMIN_API_TOKEN,MASTER_KEY_BASE64,ISSUER_PRIVATE_KEY,TREASURY_PRIVATE_KEY,SERVICE_TOKEN,AUTH_HMAC_SECRET_BASE64}"

if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: kubectl is required." >&2
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "ERROR: .env file not found: ${ENV_FILE}" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
CONFIG_ENV_FILE="${TMP_DIR}/config.env"
SECRET_ENV_FILE="${TMP_DIR}/secret.env"

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

awk \
  -v config_file="${CONFIG_ENV_FILE}" \
  -v secret_file="${SECRET_ENV_FILE}" \
  -v secret_keys_csv="${SECRET_KEYS}" \
  '
  BEGIN {
    n = split(secret_keys_csv, arr, ",");
    for (i = 1; i <= n; i++) {
      key = arr[i];
      gsub(/^[ \t]+|[ \t]+$/, "", key);
      if (key != "") {
        secret_keys[key] = 1;
      }
    }
  }
  /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
  {
    line = $0;
    sub(/^[[:space:]]*export[[:space:]]+/, "", line);
    if (line ~ /^[A-Za-z_][A-Za-z0-9_]*=/) {
      split(line, kv, "=");
      key = kv[1];
      if (key in secret_keys) {
        print line >> secret_file;
      } else {
        print line >> config_file;
      }
    }
  }
  ' "${ENV_FILE}"

if [[ ! -s "${CONFIG_ENV_FILE}" ]]; then
  echo "ERROR: no config entries were generated from ${ENV_FILE}" >&2
  exit 1
fi

if [[ ! -s "${SECRET_ENV_FILE}" ]]; then
  echo "ERROR: no secret entries were generated from ${ENV_FILE}" >&2
  exit 1
fi

kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1 || kubectl create namespace "${NAMESPACE}"

kubectl -n "${NAMESPACE}" create configmap "${CONFIGMAP_NAME}" \
  --from-env-file="${CONFIG_ENV_FILE}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "${NAMESPACE}" create secret generic "${SECRET_NAME}" \
  --from-env-file="${SECRET_ENV_FILE}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "ConfigMap applied: ${NAMESPACE}/${CONFIGMAP_NAME}"
echo "Secret applied: ${NAMESPACE}/${SECRET_NAME}"
echo "Source env file: ${ENV_FILE}"
