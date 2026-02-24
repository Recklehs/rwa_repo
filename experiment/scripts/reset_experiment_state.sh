#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE="read-model"
COMPOSE_FILE="${ROOT_DIR}/infra/docker-compose.yml"
DB_USER="rwa"
DB_NAME="rwa"
TOPIC_RAW="chain.logs.raw"
TOPIC_DLQ="chain.logs.dlq"
KAFKA_BOOTSTRAP_SERVER="kafka:9092"
RESET_KAFKA=1
RESET_DLQ=1
DELETE_STATE_FILE=1
STATE_FILE="${ROOT_DIR}/ingester/state/lastProcessedBlock.txt"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --mode <read-model|full>     Reset scope (default: read-model)
  --compose-file <path>        Docker compose file (default: infra/docker-compose.yml)
  --db-user <user>             Postgres user (default: rwa)
  --db-name <name>             Postgres DB name (default: rwa)
  --topic-raw <name>           Raw log topic to recreate (default: chain.logs.raw)
  --topic-dlq <name>           DLQ topic to recreate (default: chain.logs.dlq)
  --kafka-bootstrap <addr>     Kafka bootstrap in container network (default: kafka:9092)
  --skip-kafka                 Skip Kafka topic recreation
  --skip-dlq                   Skip DLQ topic recreation
  --keep-state-file            Keep ingester file checkpoint
  --state-file <path>          File checkpoint path (default: ingester/state/lastProcessedBlock.txt)
  -h, --help                   Show help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      MODE="${2:-}"
      shift 2
      ;;
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
    --topic-raw)
      TOPIC_RAW="${2:-}"
      shift 2
      ;;
    --topic-dlq)
      TOPIC_DLQ="${2:-}"
      shift 2
      ;;
    --kafka-bootstrap)
      KAFKA_BOOTSTRAP_SERVER="${2:-}"
      shift 2
      ;;
    --skip-kafka)
      RESET_KAFKA=0
      shift
      ;;
    --skip-dlq)
      RESET_DLQ=0
      shift
      ;;
    --keep-state-file)
      DELETE_STATE_FILE=0
      shift
      ;;
    --state-file)
      STATE_FILE="${2:-}"
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

if [[ "${MODE}" != "read-model" && "${MODE}" != "full" ]]; then
  echo "--mode must be one of: read-model, full" >&2
  exit 1
fi

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "Compose file not found: ${COMPOSE_FILE}" >&2
  exit 1
fi

COMPOSE_CMD=(docker compose -f "${COMPOSE_FILE}")

run_sql_reset() {
  local sql_file
  sql_file="${ROOT_DIR}/experiment/sql/reset_${MODE}.sql"

  if [[ ! -f "${sql_file}" ]]; then
    echo "Reset SQL not found: ${sql_file}" >&2
    exit 1
  fi

  echo "[reset] applying SQL: ${sql_file}"
  "${COMPOSE_CMD[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" -f - < "${sql_file}"
}

recreate_topic() {
  local topic="$1"

  echo "[reset] recreating topic: ${topic}"
  "${COMPOSE_CMD[@]}" exec -T kafka kafka-topics --bootstrap-server "${KAFKA_BOOTSTRAP_SERVER}" --delete --topic "${topic}" >/dev/null 2>&1 || true
  sleep 1
  "${COMPOSE_CMD[@]}" exec -T kafka kafka-topics --bootstrap-server "${KAFKA_BOOTSTRAP_SERVER}" --create --if-not-exists --topic "${topic}" --partitions 1 --replication-factor 1 >/dev/null
}

reset_checkpoint_file() {
  if [[ "${DELETE_STATE_FILE}" -eq 1 && -f "${STATE_FILE}" ]]; then
    echo "[reset] deleting checkpoint file: ${STATE_FILE}"
    rm -f "${STATE_FILE}"
  fi
}

run_sql_reset

if [[ "${RESET_KAFKA}" -eq 1 ]]; then
  recreate_topic "${TOPIC_RAW}"
  if [[ "${RESET_DLQ}" -eq 1 ]]; then
    recreate_topic "${TOPIC_DLQ}"
  fi
fi

reset_checkpoint_file

echo "[reset] done (mode=${MODE})"
