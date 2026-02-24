#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/infra/docker-compose.yml"
SCENARIO="all"
COOLDOWN_SEC=10
KAFKA_BOOTSTRAP_SERVER="kafka:9092"
KAFKA_GROUP_ID="rwa-flink-indexer"
KAFKA_TOPIC="chain.logs.raw"
KAFKA_REBALANCE_TIMEOUT_MS=15000

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Scenarios:
  flink-restart
  postgres-restart
  kafka-rebalance
  all

Options:
  --scenario <name>            Fault scenario (default: all)
  --compose-file <path>        Docker compose file (default: infra/docker-compose.yml)
  --cooldown-sec <n>           Wait between scenarios (default: 10)
  --kafka-bootstrap <addr>     Kafka bootstrap (default: kafka:9092)
  --kafka-group <id>           Group id to rebalance (default: rwa-flink-indexer)
  --kafka-topic <name>         Topic used by temporary rebalance consumer (default: chain.logs.raw)
  --rebalance-timeout-ms <n>   Timeout for temporary consumer (default: 15000)
  -h, --help                   Show help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario)
      SCENARIO="${2:-}"
      shift 2
      ;;
    --compose-file)
      COMPOSE_FILE="${2:-}"
      shift 2
      ;;
    --cooldown-sec)
      COOLDOWN_SEC="${2:-}"
      shift 2
      ;;
    --kafka-bootstrap)
      KAFKA_BOOTSTRAP_SERVER="${2:-}"
      shift 2
      ;;
    --kafka-group)
      KAFKA_GROUP_ID="${2:-}"
      shift 2
      ;;
    --kafka-topic)
      KAFKA_TOPIC="${2:-}"
      shift 2
      ;;
    --rebalance-timeout-ms)
      KAFKA_REBALANCE_TIMEOUT_MS="${2:-}"
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

case "${SCENARIO}" in
  flink-restart|postgres-restart|kafka-rebalance|all)
    ;;
  *)
    echo "Unsupported scenario: ${SCENARIO}" >&2
    exit 1
    ;;
esac

COMPOSE_CMD=(docker compose -f "${COMPOSE_FILE}")

restart_service() {
  local svc="$1"
  echo "[fault] restarting service: ${svc}"
  "${COMPOSE_CMD[@]}" restart "${svc}"
}

scenario_flink_restart() {
  restart_service flink
}

scenario_postgres_restart() {
  restart_service postgres
}

scenario_kafka_rebalance() {
  echo "[fault] triggering kafka rebalance for group=${KAFKA_GROUP_ID} topic=${KAFKA_TOPIC}"
  "${COMPOSE_CMD[@]}" exec -T kafka kafka-console-consumer \
    --bootstrap-server "${KAFKA_BOOTSTRAP_SERVER}" \
    --topic "${KAFKA_TOPIC}" \
    --group "${KAFKA_GROUP_ID}" \
    --timeout-ms "${KAFKA_REBALANCE_TIMEOUT_MS}" \
    >/dev/null 2>&1 || true
}

run_one() {
  local name="$1"
  case "${name}" in
    flink-restart)
      scenario_flink_restart
      ;;
    postgres-restart)
      scenario_postgres_restart
      ;;
    kafka-rebalance)
      scenario_kafka_rebalance
      ;;
    *)
      echo "Unknown scenario: ${name}" >&2
      exit 1
      ;;
  esac

  echo "[fault] scenario complete: ${name}"
}

if [[ "${SCENARIO}" == "all" ]]; then
  run_one flink-restart
  sleep "${COOLDOWN_SEC}"
  run_one postgres-restart
  sleep "${COOLDOWN_SEC}"
  run_one kafka-rebalance
else
  run_one "${SCENARIO}"
fi

echo "[fault] done"
