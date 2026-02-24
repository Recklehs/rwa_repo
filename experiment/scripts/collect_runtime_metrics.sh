#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/infra/docker-compose.yml"
OUTPUT_ROOT="${ROOT_DIR}/experiment/results"
DB_USER="rwa"
DB_NAME="rwa"
KAFKA_BOOTSTRAP_SERVER="kafka:9092"
KAFKA_GROUP_ID="rwa-flink-indexer"
FLINK_REST_URL="http://localhost:8081"
LABEL="snapshot"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --compose-file <path>      Docker compose file (default: infra/docker-compose.yml)
  --output-root <path>       Root output directory (default: experiment/results)
  --db-user <user>           Postgres user (default: rwa)
  --db-name <name>           Postgres DB name (default: rwa)
  --kafka-bootstrap <addr>   Kafka bootstrap in container network (default: kafka:9092)
  --kafka-group <id>         Kafka consumer group for lag check (default: rwa-flink-indexer)
  --flink-rest-url <url>     Flink REST endpoint (default: http://localhost:8081)
  --label <name>             Snapshot label for folder name (default: snapshot)
  -h, --help                 Show help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --compose-file)
      COMPOSE_FILE="${2:-}"
      shift 2
      ;;
    --output-root)
      OUTPUT_ROOT="${2:-}"
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
    --kafka-bootstrap)
      KAFKA_BOOTSTRAP_SERVER="${2:-}"
      shift 2
      ;;
    --kafka-group)
      KAFKA_GROUP_ID="${2:-}"
      shift 2
      ;;
    --flink-rest-url)
      FLINK_REST_URL="${2:-}"
      shift 2
      ;;
    --label)
      LABEL="${2:-}"
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

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required" >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 1
fi

COMPOSE_CMD=(docker compose -f "${COMPOSE_FILE}")

ts="$(date -u +%Y%m%dT%H%M%SZ)"
out_dir="${OUTPUT_ROOT}/${ts}_${LABEL}"
pg_dir="${out_dir}/postgres"
kafka_dir="${out_dir}/kafka"
flink_dir="${out_dir}/flink"

mkdir -p "${pg_dir}" "${kafka_dir}" "${flink_dir}"

echo "[metrics] writing snapshot to ${out_dir}"

# Postgres observability snapshot
"${COMPOSE_CMD[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" >/dev/null

"${COMPOSE_CMD[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" -A -F $'\t' -c "
SELECT
  calls,
  round(total_exec_time::numeric, 3) AS total_ms,
  round(mean_exec_time::numeric, 3) AS mean_ms,
  rows,
  query
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 50;
" > "${pg_dir}/top_pg_stat_statements.tsv"

"${COMPOSE_CMD[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" -A -F $'\t' -c "
SELECT relname, n_live_tup, n_dead_tup, seq_scan, idx_scan
FROM pg_stat_user_tables
ORDER BY n_live_tup DESC;
" > "${pg_dir}/table_activity.tsv"

"${COMPOSE_CMD[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}" -A -F $'\t' -c "
SELECT
  c.relname,
  pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind IN ('r', 'p')
ORDER BY pg_total_relation_size(c.oid) DESC;
" > "${pg_dir}/table_sizes.tsv"

"${COMPOSE_CMD[@]}" logs --no-color postgres | tail -n 1000 > "${pg_dir}/postgres_logs_tail.log" || true

# Kafka lag snapshot
"${COMPOSE_CMD[@]}" exec -T kafka kafka-consumer-groups --bootstrap-server "${KAFKA_BOOTSTRAP_SERVER}" --list > "${kafka_dir}/consumer_groups.txt" || true

"${COMPOSE_CMD[@]}" exec -T kafka kafka-consumer-groups --bootstrap-server "${KAFKA_BOOTSTRAP_SERVER}" --group "${KAFKA_GROUP_ID}" --describe > "${kafka_dir}/group_${KAFKA_GROUP_ID}_lag.txt" || true

"${COMPOSE_CMD[@]}" exec -T kafka kafka-topics --bootstrap-server "${KAFKA_BOOTSTRAP_SERVER}" --describe > "${kafka_dir}/topics_describe.txt" || true

# Flink checkpoint/backpressure snapshot
if curl -sf "${FLINK_REST_URL%/}/jobs/overview" > "${flink_dir}/jobs_overview.json"; then
  mapfile -t job_ids < <(jq -r '.jobs[]?.jid // empty' "${flink_dir}/jobs_overview.json")

  if [[ ${#job_ids[@]} -eq 0 ]]; then
    echo "No active Flink jobs found" > "${flink_dir}/note.txt"
  fi

  for job_id in "${job_ids[@]}"; do
    job_dir="${flink_dir}/${job_id}"
    mkdir -p "${job_dir}"

    curl -sf "${FLINK_REST_URL%/}/jobs/${job_id}" > "${job_dir}/job_detail.json" || true
    curl -sf "${FLINK_REST_URL%/}/jobs/${job_id}/checkpoints" > "${job_dir}/checkpoints.json" || true
    curl -sf "${FLINK_REST_URL%/}/jobs/${job_id}/exceptions" > "${job_dir}/exceptions.json" || true

    if [[ -f "${job_dir}/job_detail.json" ]]; then
      mapfile -t vertex_ids < <(jq -r '.vertices[]?.id // empty' "${job_dir}/job_detail.json")
      for vertex_id in "${vertex_ids[@]}"; do
        curl -sf "${FLINK_REST_URL%/}/jobs/${job_id}/vertices/${vertex_id}/backpressure" > "${job_dir}/backpressure_${vertex_id}.json" || true
      done
    fi
  done
else
  echo "Flink REST unavailable at ${FLINK_REST_URL}" > "${flink_dir}/note.txt"
fi

cat > "${out_dir}/summary.txt" <<SUMMARY
snapshot_time_utc=${ts}
compose_file=${COMPOSE_FILE}
postgres_db=${DB_NAME}
kafka_group=${KAFKA_GROUP_ID}
flink_rest=${FLINK_REST_URL}

files:
- postgres/top_pg_stat_statements.tsv
- postgres/table_activity.tsv
- postgres/table_sizes.tsv
- postgres/postgres_logs_tail.log
- kafka/consumer_groups.txt
- kafka/group_${KAFKA_GROUP_ID}_lag.txt
- kafka/topics_describe.txt
- flink/jobs_overview.json
SUMMARY

echo "[metrics] done"
