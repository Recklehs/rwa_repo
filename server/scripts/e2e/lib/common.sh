#!/usr/bin/env bash

# Shared helpers for E2E catalog scripts.

log_info() {
  printf '[e2e][INFO] %s\n' "$*"
}

log_warn() {
  printf '[e2e][WARN] %s\n' "$*" >&2
}

log_error() {
  printf '[e2e][ERROR] %s\n' "$*" >&2
}

now_ms() {
  local out
  out="$(date +%s%3N 2>/dev/null || true)"
  if [[ "$out" =~ ^[0-9]+$ ]]; then
    echo "$out"
    return 0
  fi

  if command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
    return 0
  fi

  perl -MTime::HiRes=time -e 'printf("%d\n", int(time() * 1000));'
}

require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || {
    log_error "missing command: $cmd"
    return 1
  }
}

json_bool() {
  local v="${1:-false}"
  if [[ "$v" == "true" || "$v" == "1" ]]; then
    echo "true"
  else
    echo "false"
  fi
}

append_result() {
  local suite="$1"
  local scenario="$2"
  local status="$3"
  local duration_ms="$4"
  local message="$5"
  local evidence="$6"

  if [[ -z "${E2E_RESULTS_FILE:-}" ]]; then
    log_error "E2E_RESULTS_FILE is not set"
    return 1
  fi

  jq -nc \
    --arg suite "$suite" \
    --arg scenario "$scenario" \
    --arg status "$status" \
    --argjson durationMs "${duration_ms:-0}" \
    --arg message "$message" \
    --arg evidence "$evidence" \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{suite:$suite,scenario:$scenario,status:$status,durationMs:$durationMs,message:$message,evidence:$evidence,timestamp:$ts}' \
    >> "$E2E_RESULTS_FILE"
}

http_post_json() {
  local url="$1"
  local body="$2"
  shift 2

  curl -sS -X POST "$url" \
    -H 'Content-Type: application/json' \
    "$@" \
    -d "$body"
}

http_get_json() {
  local url="$1"
  shift

  curl -sS "$url" "$@"
}

http_status_and_body() {
  local method="$1"
  local url="$2"
  local body="${3:-}"
  shift 3

  local tmp
  tmp="$(mktemp)"
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$url" "$@" -H 'Content-Type: application/json' -d "$body" -o "$tmp" -w '%{http_code}'
  else
    curl -sS -X "$method" "$url" "$@" -o "$tmp" -w '%{http_code}'
  fi
  printf '\n'
  cat "$tmp"
  rm -f "$tmp"
}
