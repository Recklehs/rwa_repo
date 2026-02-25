# AKS deploy for `flink` (Flink Operator) using `.env`

This folder applies the current `flink/.env` to AKS runtime by splitting it into:
- ConfigMap: non-sensitive keys
- Secret: sensitive keys

`FlinkDeployment` injects both with `envFrom`.

## Files

- `create-env-secret.sh`: create/update ConfigMap + Secret from `flink/.env`
- `rbac.yaml`: ServiceAccount + Role + RoleBinding template
- `pvc.yaml`: PVC for checkpoints/savepoints
- `flinkdeployment.yaml`: FlinkDeployment template (image + env + pvc placeholders)
- `deploy.sh`: preflight + apply + wait for `RUNNING`

## 0) Prerequisites

- Flink Operator installed on AKS (CRD `flinkdeployments.flink.apache.org` exists)
- Namespace `rwa` accessible
- ACR image pull permissions configured for AKS

## 1) Create or update ConfigMap + Secret from `.env`

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/flink/k8s/aks
NAMESPACE=rwa \
CONFIGMAP_NAME=rwa-flink-config \
SECRET_NAME=rwa-flink-secret \
./create-env-secret.sh
```

Defaults:
- `ENV_FILE=../../.env` (resolves to `/Users/kanghyoseung/Desktop/project/rwa/flink/.env`)
- `NAMESPACE=rwa`
- `CONFIGMAP_NAME=rwa-flink-config`
- `SECRET_NAME=rwa-flink-secret`
- `SECRET_KEYS=DB_PASSWORD,KAFKA_SASL_JAAS_CONFIG`

## 2) Build and push AKS image (`linux/amd64`)

```bash
docker buildx build \
  --platform linux/amd64 \
  -f /Users/kanghyoseung/Desktop/project/rwa/flink/Dockerfile \
  -t 2dtteam4temp.azurecr.io/rwa-flink:<YYYYMMDD-HHMM-gitsha> \
  --push \
  /Users/kanghyoseung/Desktop/project/rwa
```

Rules:
- Always use immutable tags
- Do not use `:latest`

## 3) Deploy to AKS (PVC + FlinkDeployment)

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/flink/k8s/aks
IMAGE=2dtteam4temp.azurecr.io/rwa-flink:<YYYYMMDD-HHMM-gitsha> \
NAMESPACE=rwa \
APP_NAME=rwa-flink-indexer \
CONFIGMAP_NAME=rwa-flink-config \
SECRET_NAME=rwa-flink-secret \
PVC_NAME=rwa-flink-state \
SERVICE_ACCOUNT=rwa-flink \
./deploy.sh
```

This will:
- check preconditions (`kubectl`, CRD, image, configmap/secret)
- apply runtime RBAC (`ServiceAccount`, `Role`, `RoleBinding`)
- apply `PVC`
- apply `FlinkDeployment`
- wait until `jobManagerDeploymentStatus=READY` and `jobState=RUNNING`

## 4) Verify

```bash
kubectl -n rwa get flinkdeployment rwa-flink-indexer -o wide
kubectl -n rwa get pods -l app=rwa-flink-indexer -o wide
kubectl -n rwa logs -l app=rwa-flink-indexer,component=jobmanager --all-containers=true --tail=200
```

## 5) Rollback / restart

```bash
# Rollback by setting previous image tag
kubectl -n rwa patch flinkdeployment rwa-flink-indexer --type merge \
  -p '{"spec":{"image":"2dtteam4temp.azurecr.io/rwa-flink:<previous-tag>"}}'

# Restart (last-state)
kubectl -n rwa patch flinkdeployment rwa-flink-indexer --type merge \
  -p '{"spec":{"job":{"state":"suspended"}}}'
kubectl -n rwa patch flinkdeployment rwa-flink-indexer --type merge \
  -p '{"spec":{"job":{"state":"running"}}}'
```

## Notes

- `jarURI` is fixed: `local:///opt/flink/usrlib/rwa-flink-indexer.jar`
- Image bundles `/opt/shared`; deployment sets `SHARED_DIR_PATH=/opt/shared`
- Checkpoint/savepoint dirs are fixed to `file:///flink-data/*` on PVC
