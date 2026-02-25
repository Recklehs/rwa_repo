# E2E Catalog Runner (AKS)

This directory provides an executable test catalog runner for CryptoOrder + CODEX v4.2.

## Main entrypoint

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/server/scripts/e2e
./setup-tools.sh

# (Optional but recommended) auto-generate CryptoOrder table/column mapping
./prepare-cryptoorder-sql.sh \
  --env-file /Users/kanghyoseung/Desktop/project/rwa/server/scripts/e2e/env/aks.env.example \
  --out-file /Users/kanghyoseung/Desktop/project/rwa/server/scripts/e2e/env/cryptoorder-sql.auto.env

# Load base env + generated mapping
set -a
source /Users/kanghyoseung/Desktop/project/rwa/server/scripts/e2e/env/aks.env.example
source /Users/kanghyoseung/Desktop/project/rwa/server/scripts/e2e/env/cryptoorder-sql.auto.env
set +a

./run-catalog.sh \
  --suite all \
  --env-file /Users/kanghyoseung/Desktop/project/rwa/server/scripts/e2e/env/aks.env.example \
  --report-dir /Users/kanghyoseung/Desktop/project/rwa/server/scripts/e2e/results \
  --continue-on-fail \
  --allow-disruptive \
  --cryptoorder-sql /Users/kanghyoseung/Desktop/project/rwa/server/scripts/e2e/sql/cryptoorder/cryptoorder_catalog_checks.sql
```

## CLI

- `--suite smoke|idempotency|security|legacy|concurrency|resilience|ops|all`
- `--scenario <ID[,ID...]>`
- `--env-file <path>`
- `--report-dir <path>`
- `--allow-disruptive`
- `--continue-on-fail`
- `--cryptoorder-sql <path>`

## Tool bootstrap

- `setup-tools.sh` checks newman/k6 availability using local wrappers.
- `bin/newman` uses global `newman` if installed, otherwise `npx --yes newman`.
- `bin/k6` uses global `k6` if installed, otherwise `docker run grafana/k6`.

## Outputs

Each run writes under:

- `results/<run_id>/raw/*`
- `results/<run_id>/summary.json`
- `results/<run_id>/summary.md`

## Scripts

- `preflight.sh`: environment/command/token/DB/K8s auth checks
- `run-newman.sh`: Postman/Newman suites
- `run-k6.sh`: concurrency suites with k6
- `run-db-checks.sh`: read-only SQL checks
- `prepare-cryptoorder-sql.sh`: auto-detect CryptoOrder table/column mapping for read-only SQL
- `aks/fault-inject.sh`: inject RPC/Kafka faults to AKS deployment
- `aks/fault-restore.sh`: restore deployment env/replicas from snapshot
- `report.sh`: aggregate scenario status into JSON/Markdown

## Notes

- Resilience suite requires `--allow-disruptive` (otherwise scenarios are marked `FAIL` by policy).
- AKS auth preflight expects `az login` completed and uses `AZURE_CONFIG_DIR=/tmp/azure` by default.
- `I5` and `B` DB checks depend on `--cryptoorder-sql` because CryptoOrder schema/table names are deployment-specific.
- Recommended SQL: `sql/cryptoorder/cryptoorder_catalog_checks.sql`.
- Provide table/column mapping via env vars in `env/aks.env.example` (`E2E_CRYPTOORDER_*`).
