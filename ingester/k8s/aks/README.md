# AKS deploy for `ingester` using `.env`

This folder applies the current `ingester/.env` to AKS runtime by splitting it into:
- ConfigMap: non-sensitive keys
- Secret: sensitive keys

The Deployment injects both with `envFrom`.

## Files

- `create-env-secret.sh`: create/update ConfigMap + Secret from `ingester/.env`
- `deployment.yaml`: `rwa-ingester` Deployment template (image + env placeholders)
- `deploy.sh`: render/apply Deployment and wait for rollout

## 1) Create or update ConfigMap + Secret from `.env`

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/ingester/k8s/aks
NAMESPACE=rwa \
CONFIGMAP_NAME=rwa-ingester-config \
SECRET_NAME=rwa-ingester-secret \
./create-env-secret.sh
```

Defaults:
- `ENV_FILE=../../.env` (resolves to `/Users/kanghyoseung/Desktop/project/rwa/ingester/.env`)
- `NAMESPACE=rwa`
- `CONFIGMAP_NAME=rwa-ingester-config`
- `SECRET_NAME=rwa-ingester-secret`
- `SECRET_KEYS=KAFKA_SASL_JAAS_CONFIG,DB_URL,DB_USER,DB_PASSWORD`

Validation:
- `KAFKA_CLIENT_DNS_LOOKUP` must be `use_all_dns_ips`
- `STATE_STORE` must be `postgres`

## 2) Build and push AKS image (`linux/amd64`, immutable tag)

```bash
docker buildx build \
  --platform linux/amd64 \
  -f /Users/kanghyoseung/Desktop/project/rwa/ingester/Dockerfile \
  -t <acr-name>.azurecr.io/rwa-ingester:<YYYYMMDD-HHMM-gitsha> \
  --push \
  /Users/kanghyoseung/Desktop/project/rwa
```

Rules:
- Always use immutable tags
- Do not use `:latest`

## 3) Deploy image to AKS

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/ingester/k8s/aks
IMAGE=<acr-name>.azurecr.io/rwa-ingester:<YYYYMMDD-HHMM-gitsha> \
NAMESPACE=rwa \
APP_NAME=rwa-ingester \
CONFIGMAP_NAME=rwa-ingester-config \
SECRET_NAME=rwa-ingester-secret \
./deploy.sh
```

This will:
- apply Deployment
- inject env vars from `ConfigMap/rwa-ingester-config` and `Secret/rwa-ingester-secret`
- wait for rollout success

## 4) Verify

```bash
kubectl -n rwa get deploy,pods -l app=rwa-ingester
kubectl -n rwa logs deployment/rwa-ingester --tail=200
```

Expected checks:
- no `exec format error`
- no `CrashLoopBackOff`
- startup log includes `chainId=91342` and loaded confirmations

## 5) Restart after `.env` change

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/ingester/k8s/aks
NAMESPACE=rwa CONFIGMAP_NAME=rwa-ingester-config SECRET_NAME=rwa-ingester-secret ./create-env-secret.sh
kubectl -n rwa rollout restart deployment/rwa-ingester
kubectl -n rwa rollout status deployment/rwa-ingester
```

## 6) Rollback

```bash
kubectl -n rwa rollout undo deployment/rwa-ingester
kubectl -n rwa rollout status deployment/rwa-ingester
```

## Notes

- `STATE_STORE=postgres` is the AKS baseline for durable checkpointing.
- `SHARED_DIR_PATH=/shared` is supported because the image includes `/shared`.
- If AKS cannot pull ACR image, attach ACR to AKS or configure `imagePullSecrets`.
- `rwa-ingester` runs as a single replica by design (no shard/lock coordinator yet).
