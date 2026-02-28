#!/usr/bin/env bash
set -Eeuo pipefail

# Legacy Azure/AKS path is intentionally disabled.
cat >&2 <<'EOF'
ERROR: `server/k8s/aks/create-env-secret.sh` is disabled.
Reason: Azure/AKS deployment was retired after the project presentation.
This file is kept only for historical record and must not be executed.
Use local runtime flow in the project README instead.
EOF
exit 1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$SCRIPT_DIR/../../.." && pwd)}"
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env}"

NAMESPACE="${NAMESPACE:-cryptoorder}"
APP_NAME="${APP_NAME:-cryptoorder-server}"
CONFIGMAP_NAME="${CONFIGMAP_NAME:-${APP_NAME}-config}"
SECRET_NAME="${SECRET_NAME:-${APP_NAME}-secret}"
JWT_SECRET_NAME="${JWT_SECRET_NAME:-${APP_NAME}-jwt-keys}"
JWT_MOUNT_PATH="${JWT_MOUNT_PATH:-/app/keys}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: ENV_FILE not found: $ENV_FILE" >&2
  exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: kubectl command not found" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

CONFIG_ENV_FILE="$TMP_DIR/config.env"
SECRET_ENV_FILE="$TMP_DIR/secret.env"
: > "$CONFIG_ENV_FILE"
: > "$SECRET_ENV_FILE"

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

resolve_path() {
  local input_path="$1"
  if [[ "$input_path" = /* ]]; then
    printf '%s' "$input_path"
  else
    printf '%s/%s' "$PROJECT_ROOT" "$input_path"
  fi
}

is_secret_key() {
  local key="$1"

  case "$key" in
    AUTH_ACCESS_TOKEN_TTL_SECONDS|AUTH_REFRESH_TOKEN_TTL_SECONDS|AUTH_JWT_PRIVATE_KEY_PATH|AUTH_JWT_PUBLIC_KEY_PATH|SERVICE_TOKEN_HEADER)
      return 1
      ;;
  esac

  case "$key" in
    DB_URL|DB_USERNAME|DB_PASSWORD|SERVICE_TOKEN|AUTH_HMAC_SECRET_BASE64|AUTH_JWT_PRIVATE_KEY_BASE64|AUTH_JWT_PUBLIC_KEY_BASE64|IDEMPOTENCY_RESPONSE_ENCRYPTION_KEY_BASE64|KAFKA_SASL_JAAS_CONFIG|spring.kafka.properties.sasl.jaas.config)
      return 0
      ;;
  esac

  if [[ "$key" =~ (^|[._])PASSWORD($|[._]) ]]; then
    return 0
  fi
  if [[ "$key" =~ (^|[._])SECRET($|[._]) ]]; then
    return 0
  fi
  if [[ "$key" =~ PRIVATE_KEY ]]; then
    return 0
  fi
  if [[ "$key" =~ (HMAC|JAAS|CONNECTIONSTRING) ]]; then
    return 0
  fi

  return 1
}

JWT_PRIVATE_PATH=""
JWT_PUBLIC_PATH=""

while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
  line="${raw_line%$'\r'}"

  [[ -z "${line//[[:space:]]/}" ]] && continue
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  [[ "$line" != *"="* ]] && continue

  key="$(trim "${line%%=*}")"
  value="${line#*=}"

  [[ -z "$key" ]] && continue

  case "$key" in
    AUTH_JWT_PRIVATE_KEY_PATH)
      JWT_PRIVATE_PATH="$value"
      if [[ -n "$value" ]]; then
        value="${JWT_MOUNT_PATH}/jwt_private.pem"
      fi
      ;;
    AUTH_JWT_PUBLIC_KEY_PATH)
      JWT_PUBLIC_PATH="$value"
      if [[ -n "$value" ]]; then
        value="${JWT_MOUNT_PATH}/jwt_public.pem"
      fi
      ;;
  esac

  if is_secret_key "$key"; then
    printf '%s=%s\n' "$key" "$value" >> "$SECRET_ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$CONFIG_ENV_FILE"
  fi
done < "$ENV_FILE"

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"

if [[ -s "$CONFIG_ENV_FILE" ]]; then
  kubectl -n "$NAMESPACE" create configmap "$CONFIGMAP_NAME" \
    --from-env-file="$CONFIG_ENV_FILE" \
    --dry-run=client -o yaml | kubectl apply -f -
else
  echo "WARN: no config entries found from $ENV_FILE"
fi

if [[ -s "$SECRET_ENV_FILE" ]]; then
  kubectl -n "$NAMESPACE" create secret generic "$SECRET_NAME" \
    --from-env-file="$SECRET_ENV_FILE" \
    --dry-run=client -o yaml | kubectl apply -f -
else
  echo "WARN: no secret entries found from $ENV_FILE"
fi

if [[ -n "$JWT_PRIVATE_PATH" || -n "$JWT_PUBLIC_PATH" ]]; then
  if [[ -z "$JWT_PRIVATE_PATH" || -z "$JWT_PUBLIC_PATH" ]]; then
    echo "ERROR: AUTH_JWT_PRIVATE_KEY_PATH and AUTH_JWT_PUBLIC_KEY_PATH must be set together." >&2
    exit 1
  fi

  JWT_PRIVATE_FILE="$(resolve_path "$JWT_PRIVATE_PATH")"
  JWT_PUBLIC_FILE="$(resolve_path "$JWT_PUBLIC_PATH")"

  if [[ ! -f "$JWT_PRIVATE_FILE" || ! -f "$JWT_PUBLIC_FILE" ]]; then
    echo "ERROR: JWT key files not found." >&2
    echo "  private: $JWT_PRIVATE_FILE" >&2
    echo "  public : $JWT_PUBLIC_FILE" >&2
    exit 1
  fi

  kubectl -n "$NAMESPACE" create secret generic "$JWT_SECRET_NAME" \
    --from-file=jwt_private.pem="$JWT_PRIVATE_FILE" \
    --from-file=jwt_public.pem="$JWT_PUBLIC_FILE" \
    --dry-run=client -o yaml | kubectl apply -f -
fi

echo "Applied namespace/config/secret resources:"
echo "  namespace   : $NAMESPACE"
echo "  configmap   : $CONFIGMAP_NAME"
echo "  secret      : $SECRET_NAME"
if [[ -n "$JWT_PRIVATE_PATH" ]]; then
  echo "  jwt secret  : $JWT_SECRET_NAME"
  echo "  jwt mount   : $JWT_MOUNT_PATH"
fi
