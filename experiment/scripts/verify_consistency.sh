#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/infra/docker-compose.yml"
DB_USER="rwa"
DB_NAME="rwa"
SQL_FILE="${ROOT_DIR}/experiment/sql/verify_consistency.sql"
OUT_FILE=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --compose-file <path>   Docker compose file (default: infra/docker-compose.yml)
  --db-user <user>        Postgres user (default: rwa)
  --db-name <name>        Postgres DB name (default: rwa)
  --out <path>            Save output file
  -h, --help              Show help
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
    --out)
      OUT_FILE="${2:-}"
      shift 2
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

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "Compose file not found: ${COMPOSE_FILE}" >&2
  exit 1
fi

if [[ ! -f "${SQL_FILE}" ]]; then
  echo "SQL file not found: ${SQL_FILE}" >&2
  exit 1
fi

COMPOSE_CMD=(docker compose -f "${COMPOSE_FILE}")

if [[ -n "${OUT_FILE}" ]]; then
  mkdir -p "$(dirname "${OUT_FILE}")"
  "${COMPOSE_CMD[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" -f - < "${SQL_FILE}" | tee "${OUT_FILE}"
else
  "${COMPOSE_CMD[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" -f - < "${SQL_FILE}"
fi
