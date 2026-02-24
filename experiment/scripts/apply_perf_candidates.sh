#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/infra/docker-compose.yml"
DB_USER="rwa"
DB_NAME="rwa"
APPLY_PARTITION=0
APPLY_INDEX=0
APPLY_OBSERVABILITY=0

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --compose-file <path>      Docker compose file (default: infra/docker-compose.yml)
  --db-user <user>           Postgres user (default: rwa)
  --db-name <name>           Postgres DB name (default: rwa)
  --partition                Apply V900__read_model_partition_candidates.sql
  --indexes                  Apply V901__balances_partial_indexes.sql
  --observability            Apply V902__observability_extensions.sql
  --all                      Apply all candidate SQL files
  -h, --help                 Show help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --compose-file)
      COMPOSE_FILE="${2:-}"
      shift 2
      ;;
    --db-user)
      DB_USER="${2:-}"
      shift 2
      ;;
    --db-name)
      DB_NAME="${2:-}"
      shift 2
      ;;
    --partition)
      APPLY_PARTITION=1
      shift
      ;;
    --indexes)
      APPLY_INDEX=1
      shift
      ;;
    --observability)
      APPLY_OBSERVABILITY=1
      shift
      ;;
    --all)
      APPLY_PARTITION=1
      APPLY_INDEX=1
      APPLY_OBSERVABILITY=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ ${APPLY_PARTITION} -eq 0 && ${APPLY_INDEX} -eq 0 && ${APPLY_OBSERVABILITY} -eq 0 ]]; then
  echo "Nothing selected. Use --partition, --indexes, --observability, or --all." >&2
  exit 1
fi

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "Compose file not found: ${COMPOSE_FILE}" >&2
  exit 1
fi

COMPOSE_CMD=(docker compose -f "${COMPOSE_FILE}")
run_sql() {
  local file="$1"
  echo "[perf] apply: ${file}"
  "${COMPOSE_CMD[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" -f - < "${file}"
}

if [[ ${APPLY_PARTITION} -eq 1 ]]; then
  run_sql "${ROOT_DIR}/experiment/sql/migrations/V900__read_model_partition_candidates.sql"
fi

if [[ ${APPLY_INDEX} -eq 1 ]]; then
  run_sql "${ROOT_DIR}/experiment/sql/migrations/V901__balances_partial_indexes.sql"
fi

if [[ ${APPLY_OBSERVABILITY} -eq 1 ]]; then
  run_sql "${ROOT_DIR}/experiment/sql/migrations/V902__observability_extensions.sql"
fi

echo "[perf] done"
