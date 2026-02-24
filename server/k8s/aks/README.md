# AKS deploy for `server` using `.env`

This folder applies the current `server/.env` to AKS runtime by splitting it into:
- ConfigMap: non-sensitive keys
- Secret: sensitive keys

Both are injected into the Deployment with `envFrom`.

## Files

- `create-env-secret.sh`: create/update `ConfigMap` + `Secret` from `server/.env`
- `deployment.yaml`: `rwa-server` Deployment template (image + secret placeholders)
- `service.yaml`: `ClusterIP` Service template
- `deploy.sh`: render/apply templates and wait for rollout

## 1) Create or update ConfigMap + Secret from `.env`

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/server/k8s/aks
NAMESPACE=rwa \
CONFIGMAP_NAME=rwa-server-config \
SECRET_NAME=rwa-server-secret \
./create-env-secret.sh
```

Defaults:
- `ENV_FILE=../../.env` (resolves to `/Users/kanghyoseung/Desktop/project/rwa/server/.env`)
- `NAMESPACE=rwa`
- `CONFIGMAP_NAME=rwa-server-config`
- `SECRET_NAME=rwa-server-secret`
- `SECRET_KEYS=DB_PASSWORD,GAS_STATION_PRIVATE_KEY,ADMIN_API_TOKEN,MASTER_KEY_BASE64,ISSUER_PRIVATE_KEY,TREASURY_PRIVATE_KEY,SERVICE_TOKEN,AUTH_HMAC_SECRET_BASE64`

## 2) Deploy ACR image to AKS with the Secret

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/server/k8s/aks
IMAGE=<acr-name>.azurecr.io/rwa-server:<tag> \
NAMESPACE=rwa \
APP_NAME=rwa-server \
CONFIGMAP_NAME=rwa-server-config \
SECRET_NAME=rwa-server-secret \
./deploy.sh
```

This will:
- apply Deployment/Service
- inject env vars from `ConfigMap/rwa-server-config` and `Secret/rwa-server-secret`
- wait for rollout success

## 3) Verify

```bash
kubectl -n rwa get deploy,pods,svc -l app=rwa-server
kubectl -n rwa logs deployment/rwa-server --tail=200
```

## 4) When `.env` changes

Run secret update again and restart rollout:

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/server/k8s/aks
NAMESPACE=rwa CONFIGMAP_NAME=rwa-server-config SECRET_NAME=rwa-server-secret ./create-env-secret.sh
kubectl -n rwa rollout restart deployment/rwa-server
kubectl -n rwa rollout status deployment/rwa-server
```

## Notes

- Keep real `.env` out of git. Commit only templates/scripts.
- If AKS cannot pull ACR image, attach ACR to AKS or configure `imagePullSecrets`.
- `SHARED_DIR_PATH` is currently in `.env`. Set it to a valid path inside the container image if needed.
