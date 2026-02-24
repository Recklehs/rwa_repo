#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/infra/docker-compose.yml"
RESET_MODE="read-model"
LOAD_MODE="synthetic"
RATE=500
DURATION_SEC=60
TOTAL=0
FAULT_SCENARIO="none"
FAULT_DELAY_SEC=20
APPLY_INDEX_CANDIDATES=0
APPLY_PARTITION_CANDIDATES=0
DB_USER="rwa"
DB_NAME="rwa"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --compose-file <path>            Docker compose file (default: infra/docker-compose.yml)
  --reset-mode <read-model|full>   Reset scope before run (default: read-model)
  --load-mode <synthetic|replay>   Load generator mode (default: synthetic)
  --rate <n>                       Messages per second (default: 500)
  --duration-sec <n>               Load duration seconds (default: 60)
  --total <n>                      Total messages override (default: 0 = duration based)
  --fault-scenario <name>          none|flink-restart|postgres-restart|kafka-rebalance|all (default: none)
  --fault-delay-sec <n>            Delay before fault injection (default: 20)
  --apply-index-candidates         Apply experiment/sql/migrations/V901__balances_partial_indexes.sql
  --apply-partition-candidates     Apply experiment/sql/migrations/V900__read_model_partition_candidates.sql
  --db-user <user>                 Postgres user (default: rwa)
  --db-name <name>                 Postgres DB name (default: rwa)
  -h, --help                       Show help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --compose-file)
      COMPOSE_FILE="${2:-}"
      shift 2
      ;;
    --reset-mode)
      RESET_MODE="${2:-}"
      shift 2
      ;;
    --load-mode)
      LOAD_MODE="${2:-}"
      shift 2
      ;;
    --rate)
      RATE="${2:-}"
      shift 2
      ;;
    --duration-sec)
      DURATION_SEC="${2:-}"
      shift 2
      ;;
    --total)
      TOTAL="${2:-}"
      shift 2
      ;;
    --fault-scenario)
      FAULT_SCENARIO="${2:-}"
      shift 2
      ;;
    --fault-delay-sec)
      FAULT_DELAY_SEC="${2:-}"
      shift 2
      ;;
    --apply-index-candidates)
      APPLY_INDEX_CANDIDATES=1
      shift
      ;;
    --apply-partition-candidates)
      APPLY_PARTITION_CANDIDATES=1
      shift
      ;;
    --db-user)
      DB_USER="${2:-}"
      shift 2
      ;;
    --db-name)
      DB_NAME="${2:-}"
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

COMPOSE_CMD=(docker compose -f "${COMPOSE_FILE}")

run_id="$(date -u +%Y%m%dT%H%M%SZ)_cycle"
run_dir="${ROOT_DIR}/experiment/results/${run_id}"
mkdir -p "${run_dir}"

reset_script="${ROOT_DIR}/experiment/scripts/reset_experiment_state.sh"
load_script="${ROOT_DIR}/experiment/scripts/loadgen_chain_logs.sh"
metrics_script="${ROOT_DIR}/experiment/scripts/collect_runtime_metrics.sh"
fault_script="${ROOT_DIR}/experiment/scripts/inject_faults.sh"
verify_sql="${ROOT_DIR}/experiment/sql/verify_consistency.sql"

run_sql_file() {
  local file="$1"
  echo "[cycle] apply SQL: ${file}"
  "${COMPOSE_CMD[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" -f - < "${file}"
}

echo "[cycle] run_id=${run_id}"

"${reset_script}" --mode "${RESET_MODE}" --compose-file "${COMPOSE_FILE}"

if [[ "${APPLY_PARTITION_CANDIDATES}" -eq 1 ]]; then
  run_sql_file "${ROOT_DIR}/experiment/sql/migrations/V900__read_model_partition_candidates.sql"
fi

if [[ "${APPLY_INDEX_CANDIDATES}" -eq 1 ]]; then
  run_sql_file "${ROOT_DIR}/experiment/sql/migrations/V901__balances_partial_indexes.sql"
fi

"${metrics_script}" --compose-file "${COMPOSE_FILE}" --label "pre_${run_id}" --output-root "${ROOT_DIR}/experiment/results"

fault_pid=""
if [[ "${FAULT_SCENARIO}" != "none" ]]; then
  (
    sleep "${FAULT_DELAY_SEC}"
    "${fault_script}" --compose-file "${COMPOSE_FILE}" --scenario "${FAULT_SCENARIO}"
  ) &
  fault_pid="$!"
fi

load_args=(
  --compose-file "${COMPOSE_FILE}"
  --mode "${LOAD_MODE}"
  --rate "${RATE}"
  --duration-sec "${DURATION_SEC}"
)

if [[ "${TOTAL}" != "0" ]]; then
  load_args+=(--total "${TOTAL}")
fi

"${load_script}" "${load_args[@]}" | tee "${run_dir}/loadgen.log"

if [[ -n "${fault_pid}" ]]; then
  wait "${fault_pid}" || true
fi

"${metrics_script}" --compose-file "${COMPOSE_FILE}" --label "post_${run_id}" --output-root "${ROOT_DIR}/experiment/results"

"${COMPOSE_CMD[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" -f - < "${verify_sql}" > "${run_dir}/consistency_report.txt"

cat > "${run_dir}/run_meta.txt" <<META
run_id=${run_id}
compose_file=${COMPOSE_FILE}
reset_mode=${RESET_MODE}
load_mode=${LOAD_MODE}
rate=${RATE}
duration_sec=${DURATION_SEC}
total=${TOTAL}
fault_scenario=${FAULT_SCENARIO}
fault_delay_sec=${FAULT_DELAY_SEC}
apply_index_candidates=${APPLY_INDEX_CANDIDATES}
apply_partition_candidates=${APPLY_PARTITION_CANDIDATES}
META

echo "[cycle] complete: ${run_dir}"
