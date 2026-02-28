#!/usr/bin/env bash
set -Eeuo pipefail

# Legacy Azure/AKS path is intentionally disabled.
cat >&2 <<'EOF'
ERROR: `server/k8s/aks/deploy.sh` is disabled.
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
IMAGE="${IMAGE:-}"
CONFIGMAP_NAME="${CONFIGMAP_NAME:-${APP_NAME}-config}"
SECRET_NAME="${SECRET_NAME:-${APP_NAME}-secret}"
JWT_SECRET_NAME="${JWT_SECRET_NAME:-${APP_NAME}-jwt-keys}"

REPLICAS="${REPLICAS:-1}"
SERVICE_PORT="${SERVICE_PORT:-80}"
CONTAINER_PORT="${CONTAINER_PORT:-}"
TZ="${TZ:-Asia/Seoul}"
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0}"
CPU_REQUEST="${CPU_REQUEST:-250m}"
MEMORY_REQUEST="${MEMORY_REQUEST:-512Mi}"
CPU_LIMIT="${CPU_LIMIT:-1000m}"
MEMORY_LIMIT="${MEMORY_LIMIT:-1Gi}"

HPA_ENABLED="${HPA_ENABLED:-false}"
HPA_MIN_REPLICAS="${HPA_MIN_REPLICAS:-1}"
HPA_MAX_REPLICAS="${HPA_MAX_REPLICAS:-5}"
HPA_CPU_UTILIZATION="${HPA_CPU_UTILIZATION:-70}"

if [[ -z "$IMAGE" ]]; then
  echo "ERROR: IMAGE is required. Example:" >&2
  echo "  IMAGE=2dtteam4temp.azurecr.io/cryptoorder-server:<tag> ./deploy.sh" >&2
  exit 1
fi

if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: kubectl command not found" >&2
  exit 1
fi

read_env_value() {
  local key="$1"
  local file_path="$2"
  [[ -f "$file_path" ]] || return 0

  awk -v target_key="$key" '
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    {
      sub(/\r$/, "", $0)
      pos = index($0, "=")
      if (pos == 0) { next }
      k = substr($0, 1, pos - 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", k)
      if (k == target_key) {
        print substr($0, pos + 1)
        exit
      }
    }
  ' "$file_path"
}

if [[ -z "$CONTAINER_PORT" ]]; then
  CONTAINER_PORT="$(read_env_value "SERVER_PORT" "$ENV_FILE")"
fi

CONTAINER_PORT="${CONTAINER_PORT:-8080}"

if ! [[ "$CONTAINER_PORT" =~ ^[0-9]+$ ]]; then
  echo "ERROR: CONTAINER_PORT must be numeric: $CONTAINER_PORT" >&2
  exit 1
fi

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"

if ! kubectl -n "$NAMESPACE" get configmap "$CONFIGMAP_NAME" >/dev/null 2>&1; then
  echo "ERROR: ConfigMap not found: $CONFIGMAP_NAME" >&2
  echo "Run create-env-secret.sh first." >&2
  exit 1
fi

if ! kubectl -n "$NAMESPACE" get secret "$SECRET_NAME" >/dev/null 2>&1; then
  echo "ERROR: Secret not found: $SECRET_NAME" >&2
  echo "Run create-env-secret.sh first." >&2
  exit 1
fi

CONFIGMAP_RV="$(kubectl -n "$NAMESPACE" get configmap "$CONFIGMAP_NAME" -o jsonpath='{.metadata.resourceVersion}' 2>/dev/null || true)"
SECRET_RV="$(kubectl -n "$NAMESPACE" get secret "$SECRET_NAME" -o jsonpath='{.metadata.resourceVersion}' 2>/dev/null || true)"
JWT_SECRET_RV="$(kubectl -n "$NAMESPACE" get secret "$JWT_SECRET_NAME" -o jsonpath='{.metadata.resourceVersion}' 2>/dev/null || true)"

CONFIGMAP_RV="${CONFIGMAP_RV:-none}"
SECRET_RV="${SECRET_RV:-none}"
JWT_SECRET_RV="${JWT_SECRET_RV:-none}"

kubectl -n "$NAMESPACE" apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${APP_NAME}
  labels:
    app: ${APP_NAME}
spec:
  replicas: ${REPLICAS}
  selector:
    matchLabels:
      app: ${APP_NAME}
  template:
    metadata:
      labels:
        app: ${APP_NAME}
      annotations:
        codex/configmap-resource-version: "${CONFIGMAP_RV}"
        codex/secret-resource-version: "${SECRET_RV}"
        codex/jwt-secret-resource-version: "${JWT_SECRET_RV}"
    spec:
      containers:
        - name: ${APP_NAME}
          image: ${IMAGE}
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: ${CONTAINER_PORT}
          env:
            - name: SPRING_CONFIG_IMPORT
              value: optional:file:.env[.properties],optional:configtree:/app/config/configmap/,optional:configtree:/app/config/secret/
            - name: TZ
              value: "${TZ}"
            - name: JAVA_TOOL_OPTIONS
              value: "${JAVA_TOOL_OPTIONS}"
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 20
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 6
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 40
            periodSeconds: 20
            timeoutSeconds: 5
            failureThreshold: 3
          resources:
            requests:
              cpu: ${CPU_REQUEST}
              memory: ${MEMORY_REQUEST}
            limits:
              cpu: ${CPU_LIMIT}
              memory: ${MEMORY_LIMIT}
          volumeMounts:
            - name: app-config
              mountPath: /app/config/configmap
              readOnly: true
            - name: app-secret
              mountPath: /app/config/secret
              readOnly: true
            - name: jwt-keys
              mountPath: /app/keys
              readOnly: true
      volumes:
        - name: app-config
          configMap:
            name: ${CONFIGMAP_NAME}
        - name: app-secret
          secret:
            secretName: ${SECRET_NAME}
            optional: true
        - name: jwt-keys
          secret:
            secretName: ${JWT_SECRET_NAME}
            optional: true
EOF

kubectl -n "$NAMESPACE" apply -f - <<EOF
apiVersion: v1
kind: Service
metadata:
  name: ${APP_NAME}
  labels:
    app: ${APP_NAME}
spec:
  selector:
    app: ${APP_NAME}
  ports:
    - name: http
      port: ${SERVICE_PORT}
      targetPort: http
      protocol: TCP
EOF

if [[ "$HPA_ENABLED" == "true" ]]; then
  kubectl -n "$NAMESPACE" apply -f - <<EOF
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: ${APP_NAME}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: ${APP_NAME}
  minReplicas: ${HPA_MIN_REPLICAS}
  maxReplicas: ${HPA_MAX_REPLICAS}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: ${HPA_CPU_UTILIZATION}
EOF
fi

kubectl -n "$NAMESPACE" rollout status deployment/"$APP_NAME"
kubectl -n "$NAMESPACE" get pods -l app="$APP_NAME" -o wide
kubectl -n "$NAMESPACE" get svc "$APP_NAME"

echo "Deployment complete:"
echo "  namespace : $NAMESPACE"
echo "  app       : $APP_NAME"
echo "  image     : $IMAGE"
echo "  port      : $CONTAINER_PORT"
