#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$SCRIPT_DIR/bin:$PATH"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

SUITE_OPT="all"
SCENARIO_OPT=""
ENV_FILE=""
REPORT_DIR="$SCRIPT_DIR/results"
ALLOW_DISRUPTIVE="false"
CONTINUE_ON_FAIL="false"
CRYPTOORDER_SQL=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --suite <smoke|idempotency|security|legacy|concurrency|resilience|ops|all>
  --scenario <ID[,ID...]>
  --env-file <path>
  --report-dir <path>
  --allow-disruptive
  --continue-on-fail
  --cryptoorder-sql <path>
  -h, --help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite)
      SUITE_OPT="${2:-all}"
      shift 2
      ;;
    --scenario)
      SCENARIO_OPT="${2:-}"
      shift 2
      ;;
    --env-file)
      ENV_FILE="${2:-}"
      shift 2
      ;;
    --report-dir)
      REPORT_DIR="${2:-$REPORT_DIR}"
      shift 2
      ;;
    --allow-disruptive)
      ALLOW_DISRUPTIVE="true"
      shift
      ;;
    --continue-on-fail)
      CONTINUE_ON_FAIL="true"
      shift
      ;;
    --cryptoorder-sql)
      CRYPTOORDER_SQL="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      log_error "unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

if [[ -n "$ENV_FILE" ]]; then
  if [[ ! -f "$ENV_FILE" ]]; then
    log_error "env file not found: $ENV_FILE"
    exit 1
  fi
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

RUN_ID="${E2E_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_DIR="$REPORT_DIR/$RUN_ID"
RAW_DIR="$RUN_DIR/raw"
mkdir -p "$RAW_DIR"

export E2E_RUN_ID="$RUN_ID"
export E2E_RESULTS_FILE="$RAW_DIR/results.jsonl"
touch "$E2E_RESULTS_FILE"

scenario_suite() {
  case "$1" in
    S1|S2|S3|S4|S5|S6|S7|S8|X1) echo "smoke" ;;
    I1|I2|I5|X2) echo "idempotency" ;;
    A1|A2|A3|A4|E1|E2|E3|E4|E5) echo "security" ;;
    B1|B2|B3|B5) echo "legacy" ;;
    I3|T1|T2|B4) echo "concurrency" ;;
    I4|T3|T4|T5) echo "resilience" ;;
    O1|O2) echo "ops" ;;
    *) return 1 ;;
  esac
}

REQUESTED_SCENARIOS_CSV=""
if [[ -n "$SCENARIO_OPT" ]]; then
  IFS=',' read -r -a parsed <<< "$SCENARIO_OPT"
  for s in "${parsed[@]}"; do
    s_trim="$(echo "$s" | xargs)"
    if [[ -z "$s_trim" ]]; then
      continue
    fi
    if ! scenario_suite "$s_trim" >/dev/null; then
      log_error "unknown scenario id: $s_trim"
      exit 1
    fi
    REQUESTED_SCENARIOS_CSV="${REQUESTED_SCENARIOS_CSV}|${s_trim}|"
  done
fi

suite_requested() {
  local suite="$1"
  if [[ "$SUITE_OPT" == "all" ]]; then
    return 0
  fi
  [[ "$SUITE_OPT" == "$suite" ]]
}

scenario_enabled() {
  local scenario="$1"
  local suite
  if ! suite="$(scenario_suite "$scenario")"; then
    return 1
  fi

  if ! suite_requested "$suite"; then
    return 1
  fi

  if [[ -z "$REQUESTED_SCENARIOS_CSV" ]]; then
    return 0
  fi

  [[ "$REQUESTED_SCENARIOS_CSV" == *"|${scenario}|"* ]]
}

has_enabled_scenario() {
  local scenario
  for scenario in "$@"; do
    if scenario_enabled "$scenario"; then
      return 0
    fi
  done
  return 1
}

run_cmd_rc() {
  set +e
  "$@"
  local rc=$?
  set -e
  return $rc
}

record_suite_result() {
  local suite="$1"
  local status="$2"
  local duration_ms="$3"
  local message="$4"
  local evidence="$5"
  shift 5
  local scenario

  for scenario in "$@"; do
    if scenario_enabled "$scenario"; then
      append_result "$suite" "$scenario" "$status" "$duration_ms" "$message" "$evidence"
    fi
  done
}

record_single_result() {
  local suite="$1"
  local scenario="$2"
  local status="$3"
  local duration_ms="$4"
  local message="$5"
  local evidence="$6"
  if scenario_enabled "$scenario"; then
    append_result "$suite" "$scenario" "$status" "$duration_ms" "$message" "$evidence"
  fi
}

provision_user() {
  local label="$1"
  local user_id
  user_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
  local idem="${RUN_ID}-${label}-provision"
  local body
  body="{\"userId\":\"$user_id\",\"provider\":\"${E2E_PROVIDER:-MEMBER}\",\"externalUserId\":\"${RUN_ID}-${label}\"}"

  local tmp
  tmp="$(mktemp)"
  local status
  status="$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "${E2E_RWA_BASE_URL}/internal/wallets/provision" \
    -H 'Content-Type: application/json' \
    -H "X-Service-Token: ${E2E_SERVICE_TOKEN}" \
    -H "Idempotency-Key: ${idem}" \
    -d "$body")"
  if [[ "$status" != "200" ]]; then
    log_error "provision failed status=$status body=$(cat "$tmp")"
    rm -f "$tmp"
    return 1
  fi
  rm -f "$tmp"
  echo "$user_id"
}

approve_user() {
  local user_id="$1"
  local label="$2"
  local idem="${RUN_ID}-${label}-approve"
  local body
  body="{\"userId\":\"$user_id\"}"

  local tmp
  tmp="$(mktemp)"
  local status
  status="$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "${E2E_RWA_BASE_URL}/admin/compliance/approve" \
    -H 'Content-Type: application/json' \
    -H "X-Admin-Token: ${E2E_ADMIN_TOKEN}" \
    -H "Idempotency-Key: ${idem}" \
    -d "$body")"
  if [[ "$status" != "200" ]]; then
    log_error "approve failed status=$status body=$(cat "$tmp")"
    rm -f "$tmp"
    return 1
  fi
  rm -f "$tmp"
  return 0
}

suite_failed=0

SMOKE_IDS=("S1" "S2" "S3" "S4" "S5" "S6" "S7" "S8" "X1")
IDEMPOTENCY_IDS=("I1" "I2" "I5" "X2")
SECURITY_IDS=("A1" "A2" "A3" "A4" "E1" "E2" "E3" "E4" "E5")
LEGACY_IDS=("B1" "B2" "B3" "B5")
CONCURRENCY_IDS=("I3" "T1" "T2" "B4")
RESILIENCE_IDS=("I4" "T3" "T4" "T5")
OPS_IDS=("O1" "O2")

need_newman="false"
need_k6="false"
need_kubectl="false"

if has_enabled_scenario "${SMOKE_IDS[@]}" || has_enabled_scenario "${IDEMPOTENCY_IDS[@]}" || has_enabled_scenario "${SECURITY_IDS[@]}" || has_enabled_scenario "${LEGACY_IDS[@]}" || has_enabled_scenario "${OPS_IDS[@]}"; then
  need_newman="true"
fi
if has_enabled_scenario "${CONCURRENCY_IDS[@]}"; then
  need_k6="true"
fi
if has_enabled_scenario "${RESILIENCE_IDS[@]}"; then
  need_kubectl="true"
fi

pre_start="$(now_ms)"
if run_cmd_rc "$SCRIPT_DIR/preflight.sh" --env-file "$ENV_FILE" --run-dir "$RUN_DIR" --check-newman "$need_newman" --check-k6 "$need_k6" --check-kubectl "$need_kubectl"; then
  pre_rc=0
else
  pre_rc=$?
fi
pre_dur="$(( $(now_ms) - pre_start ))"
if [[ $pre_rc -ne 0 ]]; then
  for id in "${SMOKE_IDS[@]}" "${IDEMPOTENCY_IDS[@]}" "${SECURITY_IDS[@]}" "${LEGACY_IDS[@]}" "${CONCURRENCY_IDS[@]}" "${RESILIENCE_IDS[@]}" "${OPS_IDS[@]}"; do
    if scenario_enabled "$id"; then
      append_result "preflight" "$id" "FAIL" "$pre_dur" "preflight failed" "raw/preflight.json"
    fi
  done
  suite_failed=1
  "$SCRIPT_DIR/report.sh" --run-dir "$RUN_DIR" --results-file "$E2E_RESULTS_FILE"
  exit 1
fi

# 1) smoke
if has_enabled_scenario "${SMOKE_IDS[@]}"; then
  st="$(now_ms)"
  run_cmd_rc "$SCRIPT_DIR/run-newman.sh" --suite smoke --run-dir "$RUN_DIR"
  rc=$?
  dur="$(( $(now_ms) - st ))"
  if [[ $rc -eq 0 ]]; then
    record_suite_result "smoke" "PASS" "$dur" "newman smoke passed" "raw/newman-smoke-main.json" "${SMOKE_IDS[@]}"
  else
    record_suite_result "smoke" "FAIL" "$dur" "newman smoke failed" "raw/newman-smoke-main.json" "${SMOKE_IDS[@]}"
    suite_failed=1
  fi
  if [[ $suite_failed -ne 0 && "$CONTINUE_ON_FAIL" != "true" ]]; then
    "$SCRIPT_DIR/report.sh" --run-dir "$RUN_DIR" --results-file "$E2E_RESULTS_FILE"
    exit 1
  fi
fi

# 2) idempotency
if has_enabled_scenario "${IDEMPOTENCY_IDS[@]}"; then
  nm_rc=0
  if has_enabled_scenario "I1" "I2" "X2"; then
    st="$(now_ms)"
    run_cmd_rc "$SCRIPT_DIR/run-newman.sh" --suite idempotency --run-dir "$RUN_DIR"
    nm_rc=$?
    nm_dur="$(( $(now_ms) - st ))"
  else
    nm_dur=0
  fi

  if scenario_enabled "I1"; then
    st="$(now_ms)"
    run_cmd_rc "$SCRIPT_DIR/run-db-checks.sh" --scenario I1_CUSTODY --run-dir "$RUN_DIR" --run-id "$RUN_ID"
    db_i1_rc=$?
    db_i1_dur="$(( $(now_ms) - st ))"
    if [[ $nm_rc -eq 0 && $db_i1_rc -eq 0 ]]; then
      record_single_result "idempotency" "I1" "PASS" "$((nm_dur + db_i1_dur))" "newman+db passed" "raw/newman-idempotency-k8s-check.json,raw/newman-idempotency-integration.json,raw/db-I1_CUSTODY.log"
    else
      record_single_result "idempotency" "I1" "FAIL" "$((nm_dur + db_i1_dur))" "newman or db check failed" "raw/newman-idempotency-k8s-check.json,raw/newman-idempotency-integration.json,raw/db-I1_CUSTODY.log"
      suite_failed=1
    fi
  fi

  if scenario_enabled "I2"; then
    if [[ $nm_rc -eq 0 ]]; then
      record_single_result "idempotency" "I2" "PASS" "$nm_dur" "newman idempotency passed" "raw/newman-idempotency-k8s-check.json,raw/newman-idempotency-integration.json"
    else
      record_single_result "idempotency" "I2" "FAIL" "$nm_dur" "newman idempotency failed" "raw/newman-idempotency-k8s-check.json,raw/newman-idempotency-integration.json"
      suite_failed=1
    fi
  fi

  if scenario_enabled "X2"; then
    if [[ $nm_rc -eq 0 ]]; then
      record_single_result "idempotency" "X2" "PASS" "$nm_dur" "newman idempotency replay passed" "raw/newman-idempotency-k8s-check.json,raw/newman-idempotency-integration.json"
    else
      record_single_result "idempotency" "X2" "FAIL" "$nm_dur" "newman idempotency replay failed" "raw/newman-idempotency-k8s-check.json,raw/newman-idempotency-integration.json"
      suite_failed=1
    fi
  fi

  if scenario_enabled "I5"; then
    st="$(now_ms)"
    run_cmd_rc "$SCRIPT_DIR/run-db-checks.sh" --scenario I5_CRYPTOORDER --run-dir "$RUN_DIR" --run-id "$RUN_ID" --cryptoorder-sql "$CRYPTOORDER_SQL"
    i5_rc=$?
    i5_dur="$(( $(now_ms) - st ))"
    if [[ $i5_rc -eq 0 ]]; then
      record_single_result "idempotency" "I5" "PASS" "$i5_dur" "cryptoorder sql check passed" "raw/db-I5_CRYPTOORDER.log"
    else
      record_single_result "idempotency" "I5" "FAIL" "$i5_dur" "cryptoorder sql check failed (or sql path missing)" "raw/db-I5_CRYPTOORDER.log"
      suite_failed=1
    fi
  fi

  if [[ $suite_failed -ne 0 && "$CONTINUE_ON_FAIL" != "true" ]]; then
    "$SCRIPT_DIR/report.sh" --run-dir "$RUN_DIR" --results-file "$E2E_RESULTS_FILE"
    exit 1
  fi
fi

# 3) security
if has_enabled_scenario "${SECURITY_IDS[@]}"; then
  st="$(now_ms)"
  run_cmd_rc "$SCRIPT_DIR/run-newman.sh" --suite security --run-dir "$RUN_DIR"
  rc=$?
  dur="$(( $(now_ms) - st ))"
  if [[ $rc -eq 0 ]]; then
    record_suite_result "security" "PASS" "$dur" "security boundary checks passed" "raw/newman-security-boundary.json" "${SECURITY_IDS[@]}"
  else
    record_suite_result "security" "FAIL" "$dur" "security boundary checks failed" "raw/newman-security-boundary.json" "${SECURITY_IDS[@]}"
    suite_failed=1
  fi
  if [[ $suite_failed -ne 0 && "$CONTINUE_ON_FAIL" != "true" ]]; then
    "$SCRIPT_DIR/report.sh" --run-dir "$RUN_DIR" --results-file "$E2E_RESULTS_FILE"
    exit 1
  fi
fi

# 4) legacy
if has_enabled_scenario "${LEGACY_IDS[@]}"; then
  st="$(now_ms)"
  run_cmd_rc "$SCRIPT_DIR/run-newman.sh" --suite legacy --run-dir "$RUN_DIR"
  nm_rc=$?
  nm_dur="$(( $(now_ms) - st ))"

  st="$(now_ms)"
  run_cmd_rc "$SCRIPT_DIR/run-db-checks.sh" --scenario B_LEGACY_CRYPTOORDER --run-dir "$RUN_DIR" --run-id "$RUN_ID" --cryptoorder-sql "$CRYPTOORDER_SQL"
  db_rc=$?
  db_dur="$(( $(now_ms) - st ))"

  if [[ $nm_rc -eq 0 && $db_rc -eq 0 ]]; then
    record_suite_result "legacy" "PASS" "$((nm_dur + db_dur))" "legacy newman+db passed" "raw/newman-legacy-legacy.json,raw/db-B_LEGACY_CRYPTOORDER.log" "${LEGACY_IDS[@]}"
  else
    record_suite_result "legacy" "FAIL" "$((nm_dur + db_dur))" "legacy newman or db failed" "raw/newman-legacy-legacy.json,raw/db-B_LEGACY_CRYPTOORDER.log" "${LEGACY_IDS[@]}"
    suite_failed=1
  fi

  if [[ $suite_failed -ne 0 && "$CONTINUE_ON_FAIL" != "true" ]]; then
    "$SCRIPT_DIR/report.sh" --run-dir "$RUN_DIR" --results-file "$E2E_RESULTS_FILE"
    exit 1
  fi
fi

# 5) concurrency
if has_enabled_scenario "${CONCURRENCY_IDS[@]}"; then
  if scenario_enabled "I3"; then
    st="$(now_ms)"
    run_cmd_rc "$SCRIPT_DIR/run-k6.sh" --scenario I3 --run-dir "$RUN_DIR"
    rc=$?
    dur="$(( $(now_ms) - st ))"
    if [[ $rc -eq 0 ]]; then
      record_single_result "concurrency" "I3" "PASS" "$dur" "k6 I3 passed" "raw/k6-I3.json"
    else
      record_single_result "concurrency" "I3" "FAIL" "$dur" "k6 I3 failed" "raw/k6-I3.json"
      suite_failed=1
    fi
  fi

  if scenario_enabled "T1"; then
    st="$(now_ms)"
    run_cmd_rc "$SCRIPT_DIR/run-k6.sh" --scenario T1 --run-dir "$RUN_DIR"
    rc=$?
    dur="$(( $(now_ms) - st ))"
    if [[ $rc -eq 0 ]]; then
      record_single_result "concurrency" "T1" "PASS" "$dur" "k6 T1 passed" "raw/k6-T1.json"
    else
      record_single_result "concurrency" "T1" "FAIL" "$dur" "k6 T1 failed" "raw/k6-T1.json"
      suite_failed=1
    fi
  fi

  if scenario_enabled "T2"; then
    st="$(now_ms)"
    run_cmd_rc "$SCRIPT_DIR/run-k6.sh" --scenario T2 --run-dir "$RUN_DIR"
    rc=$?
    dur="$(( $(now_ms) - st ))"
    if [[ $rc -eq 0 ]]; then
      record_single_result "concurrency" "T2" "PASS" "$dur" "k6 T2 passed" "raw/k6-T2.json"
    else
      record_single_result "concurrency" "T2" "FAIL" "$dur" "k6 T2 failed" "raw/k6-T2.json"
      suite_failed=1
    fi
  fi

  if scenario_enabled "B4"; then
    st="$(now_ms)"
    run_cmd_rc "$SCRIPT_DIR/run-k6.sh" --scenario B4 --run-dir "$RUN_DIR"
    k6_rc=$?
    k6_dur="$(( $(now_ms) - st ))"

    st="$(now_ms)"
    run_cmd_rc "$SCRIPT_DIR/run-db-checks.sh" --scenario B4_CUSTODY --run-dir "$RUN_DIR" --run-id "$RUN_ID"
    db_rc=$?
    db_dur="$(( $(now_ms) - st ))"

    if [[ $k6_rc -eq 0 && $db_rc -eq 0 ]]; then
      record_single_result "concurrency" "B4" "PASS" "$((k6_dur + db_dur))" "k6+db B4 passed" "raw/k6-B4.json,raw/db-B4_CUSTODY.log"
    else
      record_single_result "concurrency" "B4" "FAIL" "$((k6_dur + db_dur))" "k6 or db B4 failed" "raw/k6-B4.json,raw/db-B4_CUSTODY.log"
      suite_failed=1
    fi
  fi

  if [[ $suite_failed -ne 0 && "$CONTINUE_ON_FAIL" != "true" ]]; then
    "$SCRIPT_DIR/report.sh" --run-dir "$RUN_DIR" --results-file "$E2E_RESULTS_FILE"
    exit 1
  fi
fi

# 6) resilience
if has_enabled_scenario "${RESILIENCE_IDS[@]}"; then
  if [[ "$ALLOW_DISRUPTIVE" != "true" && "${E2E_ALLOW_DISRUPTIVE:-false}" != "true" ]]; then
    for scenario in "${RESILIENCE_IDS[@]}"; do
      if scenario_enabled "$scenario"; then
        append_result "resilience" "$scenario" "FAIL" 0 "--allow-disruptive is required for resilience suite" ""
      fi
    done
    suite_failed=1
  else
    if scenario_enabled "I4"; then
      append_result "resilience" "I4" "FAIL" 0 "stale IN_PROGRESS auto-recovery is not implemented in custody idempotency" ""
      suite_failed=1
    fi

    if scenario_enabled "T4"; then
      st="$(now_ms)"
      t4_snapshot="$RAW_DIR/aks-snapshot-t4.json"
      t4_prefix="${RUN_ID}-t4-rpc-fail"
      t4_user=""
      t4_ok=1

      if t4_user="$(provision_user t4)" && approve_user "$t4_user" t4; then
        :
      else
        t4_ok=0
      fi

      if [[ $t4_ok -eq 1 ]]; then
        run_cmd_rc "$SCRIPT_DIR/aks/fault-inject.sh" --mode rpc --namespace "${E2E_AKS_NAMESPACE:-rwa}" --deployment "${E2E_AKS_RWA_DEPLOYMENT:-rwa-server}" --snapshot-file "$t4_snapshot"
        inject_rc=$?
        if [[ $inject_rc -ne 0 ]]; then
          t4_ok=0
        fi
      fi

      if [[ $t4_ok -eq 1 ]]; then
        tmp="$(mktemp)"
        status="$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "${E2E_RWA_BASE_URL}/admin/faucet/musd" \
          -H 'Content-Type: application/json' \
          -H "X-Admin-Token: ${E2E_ADMIN_TOKEN}" \
          -H "Idempotency-Key: ${t4_prefix}" \
          -d "{\"toUserId\":\"${t4_user}\",\"amountHuman\":1}")"
        if [[ "$status" -lt 400 ]]; then
          log_error "T4 expected failure while RPC is broken. status=$status body=$(cat "$tmp")"
          t4_ok=0
        fi
        rm -f "$tmp"
      fi

      if [[ $t4_ok -eq 1 ]]; then
        run_cmd_rc "$SCRIPT_DIR/run-db-checks.sh" --scenario T4_CUSTODY --run-dir "$RUN_DIR" --run-id "$RUN_ID" --request-prefix "$t4_prefix"
        if [[ $? -ne 0 ]]; then
          t4_ok=0
        fi
      fi

      run_cmd_rc "$SCRIPT_DIR/aks/fault-restore.sh" --namespace "${E2E_AKS_NAMESPACE:-rwa}" --deployment "${E2E_AKS_RWA_DEPLOYMENT:-rwa-server}" --snapshot-file "$t4_snapshot"
      if [[ $? -ne 0 ]]; then
        t4_ok=0
      fi

      if [[ $t4_ok -eq 1 ]]; then
        tmp="$(mktemp)"
        status="$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "${E2E_RWA_BASE_URL}/admin/faucet/musd" \
          -H 'Content-Type: application/json' \
          -H "X-Admin-Token: ${E2E_ADMIN_TOKEN}" \
          -H "Idempotency-Key: ${RUN_ID}-t4-rpc-recover" \
          -d "{\"toUserId\":\"${t4_user}\",\"amountHuman\":1}")"
        if [[ "$status" != "200" ]]; then
          log_error "T4 recovery request failed. status=$status body=$(cat "$tmp")"
          t4_ok=0
        fi
        rm -f "$tmp"
      fi

      dur="$(( $(now_ms) - st ))"
      if [[ $t4_ok -eq 1 ]]; then
        append_result "resilience" "T4" "PASS" "$dur" "RPC fault/recovery scenario passed" "raw/aks-snapshot-t4.json,raw/db-T4_CUSTODY.log"
      else
        append_result "resilience" "T4" "FAIL" "$dur" "RPC fault/recovery scenario failed" "raw/aks-snapshot-t4.json,raw/db-T4_CUSTODY.log"
        suite_failed=1
      fi
    fi

    if scenario_enabled "T5"; then
      st="$(now_ms)"
      t5_snapshot="$RAW_DIR/aks-snapshot-t5.json"
      t5_user=""
      t5_ok=1
      t5_poll_max="${E2E_T5_MAX_WAIT_SEC:-240}"
      t5_poll_int="${E2E_T5_POLL_SEC:-10}"

      if t5_user="$(provision_user t5)"; then
        :
      else
        t5_ok=0
      fi

      if [[ $t5_ok -eq 1 ]]; then
        run_cmd_rc "$SCRIPT_DIR/aks/fault-inject.sh" --mode kafka --namespace "${E2E_AKS_NAMESPACE:-rwa}" --deployment "${E2E_AKS_RWA_DEPLOYMENT:-rwa-server}" --snapshot-file "$t5_snapshot"
        if [[ $? -ne 0 ]]; then
          t5_ok=0
        fi
      fi

      if [[ $t5_ok -eq 1 ]]; then
        if ! approve_user "$t5_user" t5; then
          t5_ok=0
        fi
      fi

      if [[ $t5_ok -eq 1 ]]; then
        run_cmd_rc "$SCRIPT_DIR/run-db-checks.sh" --scenario T5_FAIL_CUSTODY --run-dir "$RUN_DIR" --run-id "$RUN_ID" --aggregate-user-id "$t5_user"
        if [[ $? -ne 0 ]]; then
          t5_ok=0
        fi
      fi

      run_cmd_rc "$SCRIPT_DIR/aks/fault-restore.sh" --namespace "${E2E_AKS_NAMESPACE:-rwa}" --deployment "${E2E_AKS_RWA_DEPLOYMENT:-rwa-server}" --snapshot-file "$t5_snapshot"
      if [[ $? -ne 0 ]]; then
        t5_ok=0
      fi

      if [[ $t5_ok -eq 1 ]]; then
        elapsed=0
        converged=0
        while [[ $elapsed -le $t5_poll_max ]]; do
          run_cmd_rc "$SCRIPT_DIR/run-db-checks.sh" --scenario T5_SUCCESS_CUSTODY --run-dir "$RUN_DIR" --run-id "$RUN_ID" --aggregate-user-id "$t5_user"
          if [[ $? -eq 0 ]]; then
            converged=1
            break
          fi
          sleep "$t5_poll_int"
          elapsed=$((elapsed + t5_poll_int))
        done
        if [[ $converged -ne 1 ]]; then
          t5_ok=0
        fi
      fi

      dur="$(( $(now_ms) - st ))"
      if [[ $t5_ok -eq 1 ]]; then
        append_result "resilience" "T5" "PASS" "$dur" "Kafka fault/recovery scenario passed" "raw/aks-snapshot-t5.json,raw/db-T5_FAIL_CUSTODY.log,raw/db-T5_SUCCESS_CUSTODY.log"
      else
        append_result "resilience" "T5" "FAIL" "$dur" "Kafka fault/recovery scenario failed" "raw/aks-snapshot-t5.json,raw/db-T5_FAIL_CUSTODY.log,raw/db-T5_SUCCESS_CUSTODY.log"
        suite_failed=1
      fi
    fi

    if scenario_enabled "T3"; then
      st="$(now_ms)"
      run_cmd_rc "$SCRIPT_DIR/run-db-checks.sh" --scenario T3_CUSTODY --run-dir "$RUN_DIR" --run-id "$RUN_ID"
      rc=$?
      dur="$(( $(now_ms) - st ))"
      if [[ $rc -eq 0 ]]; then
        append_result "resilience" "T3" "PASS" "$dur" "outbox state machine check passed" "raw/db-T3_CUSTODY.log"
      else
        append_result "resilience" "T3" "FAIL" "$dur" "outbox state machine check failed" "raw/db-T3_CUSTODY.log"
        suite_failed=1
      fi
    fi
  fi

  if [[ $suite_failed -ne 0 && "$CONTINUE_ON_FAIL" != "true" ]]; then
    "$SCRIPT_DIR/report.sh" --run-dir "$RUN_DIR" --results-file "$E2E_RESULTS_FILE"
    exit 1
  fi
fi

# 7) ops
if has_enabled_scenario "${OPS_IDS[@]}"; then
  st="$(now_ms)"
  run_cmd_rc "$SCRIPT_DIR/run-newman.sh" --suite ops --run-dir "$RUN_DIR"
  rc=$?
  dur="$(( $(now_ms) - st ))"
  if [[ $rc -eq 0 ]]; then
    record_suite_result "ops" "PASS" "$dur" "ops checks passed" "raw/newman-ops-ops.json" "${OPS_IDS[@]}"
  else
    record_suite_result "ops" "FAIL" "$dur" "ops checks failed" "raw/newman-ops-ops.json" "${OPS_IDS[@]}"
    suite_failed=1
  fi
fi

# 8) report
"$SCRIPT_DIR/report.sh" --run-dir "$RUN_DIR" --results-file "$E2E_RESULTS_FILE"

overall="$(jq -r '.status' "$RUN_DIR/summary.json")"
if [[ "$overall" == "FAIL" ]]; then
  exit 1
fi

exit 0
