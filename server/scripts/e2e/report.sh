#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

RUN_DIR=""
RESULTS_FILE=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") --run-dir <path> [--results-file <path>]
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-dir)
      RUN_DIR="${2:-}"
      shift 2
      ;;
    --results-file)
      RESULTS_FILE="${2:-}"
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

if [[ -z "$RUN_DIR" ]]; then
  usage
  exit 1
fi

if [[ -z "$RESULTS_FILE" ]]; then
  RESULTS_FILE="$RUN_DIR/raw/results.jsonl"
fi

mkdir -p "$RUN_DIR/raw"
if [[ ! -f "$RESULTS_FILE" ]]; then
  touch "$RESULTS_FILE"
fi

summary_json="$RUN_DIR/summary.json"
summary_md="$RUN_DIR/summary.md"

jq -s '
  def fold_scenario(items): {
    scenario: items[0].scenario,
    suite: items[0].suite,
    status: (if any(items[]; .status == "FAIL") then "FAIL" else "PASS" end),
    durationMs: (items | map(.durationMs // 0) | add),
    evidence: (items | map(.evidence) | map(select(. != "")) | unique),
    message: (items | map(.message) | map(select(. != "")) | unique | join(" | "))
  };

  . as $items
  | ($items | sort_by(.scenario, .timestamp) | group_by(.scenario) | map(fold_scenario(.))) as $scenarios
  | {
      generatedAt: (now | todate),
      total: ($scenarios | length),
      passed: ($scenarios | map(select(.status == "PASS")) | length),
      failed: ($scenarios | map(select(.status == "FAIL")) | length),
      status: (if ($scenarios | any(.status == "FAIL")) then "FAIL" else "PASS" end),
      scenarios: $scenarios
    }
' "$RESULTS_FILE" > "$summary_json"

{
  echo "# E2E Catalog Summary"
  echo
  echo "- GeneratedAt: $(jq -r '.generatedAt' "$summary_json")"
  echo "- Overall: $(jq -r '.status' "$summary_json")"
  echo "- Passed: $(jq -r '.passed' "$summary_json")"
  echo "- Failed: $(jq -r '.failed' "$summary_json")"
  echo
  echo "| Scenario | Suite | Status | Duration(ms) | Evidence | Message |"
  echo "|---|---|---|---:|---|---|"
  jq -r '.scenarios[] | [
      .scenario,
      .suite,
      .status,
      (.durationMs|tostring),
      (.evidence|join("<br>")),
      (.message|gsub("\\n";" "))
    ] | "| " + (join(" | ")) + " |"' "$summary_json"
} > "$summary_md"

log_info "summary generated: $summary_json"
log_info "summary generated: $summary_md"
