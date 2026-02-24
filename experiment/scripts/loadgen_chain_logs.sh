#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/infra/docker-compose.yml"
BOOTSTRAP_SERVER="kafka:9092"
TOPIC="chain.logs.raw"
MODE="synthetic"
RATE_PER_SEC=100
TOTAL_MESSAGES=0
DURATION_SEC=60
DURATION_SPECIFIED=0
TOTAL_SPECIFIED=0
CHAIN_ID=91342
NETWORK="giwaSepolia"
BLOCK_START=20000000
BLOCK_SPAN=50
LOG_INDEX_START=0
TOKEN_ID_BASE=100000
AMOUNT_WEI=100000000000000000
FROM_ADDRESS="0x1111111111111111111111111111111111111111"
TO_ADDRESS="0x2222222222222222222222222222222222222222"
OPERATOR_ADDRESS="0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
REPLAY_FILE="${ROOT_DIR}/experiment/workloads/sample_chain_logs.ndjson"
REPLAY_REWRITE_KEYS=1

DEPLOY_FILE="${ROOT_DIR}/shared/deployments/giwa-sepolia.json"
SIGNATURES_FILE="${ROOT_DIR}/shared/events/signatures.json"
CONTRACT_ADDRESS_OVERRIDE=""
TOPIC0_OVERRIDE=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Modes:
  synthetic               Generate valid ERC-1155 TransferSingle envelopes
  replay                  Replay NDJSON messages (optionally rewriting tx/log/block keys)

Options:
  --mode <synthetic|replay>     Load generation mode (default: synthetic)
  --rate <n>                    Messages per second (default: 100)
  --total <n>                   Total messages to send (default: unlimited within duration)
  --duration-sec <n>            Duration in seconds (default: 60)
  --topic <name>                Kafka topic (default: chain.logs.raw)
  --compose-file <path>         Docker compose file (default: infra/docker-compose.yml)
  --bootstrap-server <addr>     Bootstrap server inside compose network (default: kafka:9092)
  --chain-id <n>                Chain ID in payload (default: 91342)
  --network <name>              Network field in payload (default: giwaSepolia)
  --block-start <n>             First block number (default: 20000000)
  --block-span <n>              Messages per block increment (default: 50)
  --token-id-base <n>           Base token id for synthetic mode (default: 100000)
  --amount-wei <n>              Transfer amount for synthetic mode (default: 1e17)
  --from <address>              Transfer from address
  --to <address>                Transfer to address
  --operator <address>          Transfer operator address
  --contract-address <address>  Override PropertyShare1155 address
  --topic0 <hash>               Override TransferSingle topic0
  --replay-file <path>          NDJSON replay source (default: experiment/workloads/sample_chain_logs.ndjson)
  --no-replay-key-rewrite       Replay original tx/log/block fields as-is
  -h, --help                    Show help
USAGE
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

normalize_address() {
  local raw="${1#0x}"
  raw="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  if [[ ${#raw} -ne 40 ]]; then
    echo "Invalid address length: $1" >&2
    exit 1
  fi
  printf '0x%s' "$raw"
}

address_to_topic() {
  local addr
  addr="$(normalize_address "$1")"
  local raw="${addr#0x}"
  printf '0x%024d%s' 0 "$raw"
}

u256_slot_hex() {
  local value="$1"
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    printf '%064x' "$value"
    return
  fi
  echo "u256 value must be decimal integer: $value" >&2
  exit 1
}

load_contract_metadata() {
  if [[ -n "${CONTRACT_ADDRESS_OVERRIDE}" ]]; then
    CONTRACT_ADDRESS="$(normalize_address "${CONTRACT_ADDRESS_OVERRIDE}")"
  else
    CONTRACT_ADDRESS="$(jq -r '.contracts.PropertyShare1155' "${DEPLOY_FILE}")"
    CONTRACT_ADDRESS="$(normalize_address "${CONTRACT_ADDRESS}")"
  fi

  if [[ -n "${TOPIC0_OVERRIDE}" ]]; then
    TOPIC0="$(printf '%s' "${TOPIC0_OVERRIDE}" | tr '[:upper:]' '[:lower:]')"
  else
    TOPIC0="$(jq -r '.events[] | select(.id=="share.transfer_single") | .topic0' "${SIGNATURES_FILE}")"
    TOPIC0="$(printf '%s' "${TOPIC0}" | tr '[:upper:]' '[:lower:]')"
  fi

  if [[ -z "${TOPIC0}" || "${TOPIC0}" == "null" ]]; then
    echo "Failed to resolve TransferSingle topic0 from ${SIGNATURES_FILE}" >&2
    exit 1
  fi
}

build_synthetic_message() {
  local seq="$1"
  local tx_hash
  local log_index
  local block_number
  local block_hash
  local token_id
  local timestamp
  local from_topic
  local to_topic
  local operator_topic
  local data
  local dedup_key

  tx_hash="$(printf '0x%064x' "$((seq + 1))")"
  log_index="$((LOG_INDEX_START + (seq % 1000000)))"
  block_number="$((BLOCK_START + (seq / BLOCK_SPAN)))"
  block_hash="$(printf '0x%064x' "${block_number}")"
  token_id="$((TOKEN_ID_BASE + (seq % 5000)))"
  timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  from_topic="$(address_to_topic "${FROM_ADDRESS}")"
  to_topic="$(address_to_topic "${TO_ADDRESS}")"
  operator_topic="$(address_to_topic "${OPERATOR_ADDRESS}")"

  data="0x$(u256_slot_hex "${token_id}")$(u256_slot_hex "${AMOUNT_WEI}")"
  dedup_key="${CHAIN_ID}:${tx_hash}:${log_index}"

  cat <<JSON
{"chainId":${CHAIN_ID},"network":"${NETWORK}","blockNumber":${block_number},"blockHash":"${block_hash}","blockTimestamp":"${timestamp}","txHash":"${tx_hash}","txIndex":0,"logIndex":${log_index},"contractAddress":"${CONTRACT_ADDRESS}","topics":["${TOPIC0}","${operator_topic}","${from_topic}","${to_topic}"],"data":"${data}","removed":false,"dedupKey":"${dedup_key}","eventKnown":true,"eventId":"share.transfer_single","eventContract":"PropertyShare1155","eventSignature":"TransferSingle(address,address,address,uint256,uint256)","eventTopic0":"${TOPIC0}","ingestedAt":"${timestamp}"}
JSON
}

load_replay_messages() {
  if [[ ! -f "${REPLAY_FILE}" ]]; then
    echo "Replay file not found: ${REPLAY_FILE}" >&2
    exit 1
  fi

  mapfile -t REPLAY_MESSAGES < <(grep -vE '^[[:space:]]*(#|$)' "${REPLAY_FILE}")
  if [[ ${#REPLAY_MESSAGES[@]} -eq 0 ]]; then
    echo "Replay file is empty: ${REPLAY_FILE}" >&2
    exit 1
  fi
}

build_replay_message() {
  local seq="$1"
  local line

  line="${REPLAY_MESSAGES[$((seq % ${#REPLAY_MESSAGES[@]}))]}"
  if [[ "${REPLAY_REWRITE_KEYS}" -eq 0 ]]; then
    printf '%s\n' "${line}"
    return
  fi

  local tx_hash
  local log_index
  local block_number
  local block_hash
  local timestamp
  local dedup_key

  tx_hash="$(printf '0x%064x' "$((seq + 1))")"
  log_index="$((LOG_INDEX_START + (seq % 1000000)))"
  block_number="$((BLOCK_START + (seq / BLOCK_SPAN)))"
  block_hash="$(printf '0x%064x' "${block_number}")"
  timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  dedup_key="${CHAIN_ID}:${tx_hash}:${log_index}"

  printf '%s\n' "${line}" | jq -c \
    --arg txHash "${tx_hash}" \
    --arg blockHash "${block_hash}" \
    --arg blockTimestamp "${timestamp}" \
    --arg dedupKey "${dedup_key}" \
    --arg network "${NETWORK}" \
    --argjson chainId "${CHAIN_ID}" \
    --argjson blockNumber "${block_number}" \
    --argjson logIndex "${log_index}" \
    '.chainId=$chainId
     | .network=$network
     | .txHash=$txHash
     | .logIndex=$logIndex
     | .blockNumber=$blockNumber
     | .blockHash=$blockHash
     | .blockTimestamp=$blockTimestamp
     | .removed=false
     | .dedupKey=$dedupKey
     | .ingestedAt=$blockTimestamp'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      MODE="${2:-}"
      shift 2
      ;;
    --rate)
      RATE_PER_SEC="${2:-}"
      shift 2
      ;;
    --total)
      TOTAL_MESSAGES="${2:-}"
      TOTAL_SPECIFIED=1
      shift 2
      ;;
    --duration-sec)
      DURATION_SEC="${2:-}"
      DURATION_SPECIFIED=1
      shift 2
      ;;
    --topic)
      TOPIC="${2:-}"
      shift 2
      ;;
    --compose-file)
      COMPOSE_FILE="${2:-}"
      shift 2
      ;;
    --bootstrap-server)
      BOOTSTRAP_SERVER="${2:-}"
      shift 2
      ;;
    --chain-id)
      CHAIN_ID="${2:-}"
      shift 2
      ;;
    --network)
      NETWORK="${2:-}"
      shift 2
      ;;
    --block-start)
      BLOCK_START="${2:-}"
      shift 2
      ;;
    --block-span)
      BLOCK_SPAN="${2:-}"
      shift 2
      ;;
    --token-id-base)
      TOKEN_ID_BASE="${2:-}"
      shift 2
      ;;
    --amount-wei)
      AMOUNT_WEI="${2:-}"
      shift 2
      ;;
    --from)
      FROM_ADDRESS="${2:-}"
      shift 2
      ;;
    --to)
      TO_ADDRESS="${2:-}"
      shift 2
      ;;
    --operator)
      OPERATOR_ADDRESS="${2:-}"
      shift 2
      ;;
    --contract-address)
      CONTRACT_ADDRESS_OVERRIDE="${2:-}"
      shift 2
      ;;
    --topic0)
      TOPIC0_OVERRIDE="${2:-}"
      shift 2
      ;;
    --replay-file)
      REPLAY_FILE="${2:-}"
      shift 2
      ;;
    --no-replay-key-rewrite)
      REPLAY_REWRITE_KEYS=0
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

if [[ "${MODE}" != "synthetic" && "${MODE}" != "replay" ]]; then
  echo "--mode must be synthetic or replay" >&2
  exit 1
fi

if [[ ! "${RATE_PER_SEC}" =~ ^[1-9][0-9]*$ ]]; then
  echo "--rate must be a positive integer" >&2
  exit 1
fi

if [[ ! "${BLOCK_SPAN}" =~ ^[1-9][0-9]*$ ]]; then
  echo "--block-span must be a positive integer" >&2
  exit 1
fi

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "Compose file not found: ${COMPOSE_FILE}" >&2
  exit 1
fi

if [[ "${TOTAL_SPECIFIED}" -eq 1 && "${DURATION_SPECIFIED}" -eq 0 ]]; then
  DURATION_SEC=0
fi

if [[ "${TOTAL_MESSAGES}" != "0" && ! "${TOTAL_MESSAGES}" =~ ^[1-9][0-9]*$ ]]; then
  echo "--total must be 0 or a positive integer" >&2
  exit 1
fi

if [[ "${DURATION_SEC}" != "0" && ! "${DURATION_SEC}" =~ ^[1-9][0-9]*$ ]]; then
  echo "--duration-sec must be 0 or a positive integer" >&2
  exit 1
fi

require_cmd docker
require_cmd jq

COMPOSE_CMD=(docker compose -f "${COMPOSE_FILE}")

FROM_ADDRESS="$(normalize_address "${FROM_ADDRESS}")"
TO_ADDRESS="$(normalize_address "${TO_ADDRESS}")"
OPERATOR_ADDRESS="$(normalize_address "${OPERATOR_ADDRESS}")"
load_contract_metadata

if [[ "${MODE}" == "replay" ]]; then
  load_replay_messages
fi

batch_file="$(mktemp)"
trap 'rm -f "${batch_file}"' EXIT

sent=0
start_epoch="$(date +%s)"

echo "[loadgen] mode=${MODE} topic=${TOPIC} rate=${RATE_PER_SEC}/s total=${TOTAL_MESSAGES} duration=${DURATION_SEC}s"

while true; do
  now_epoch="$(date +%s)"
  elapsed="$((now_epoch - start_epoch))"

  if [[ "${DURATION_SEC}" != "0" && "${elapsed}" -ge "${DURATION_SEC}" ]]; then
    break
  fi
  if [[ "${TOTAL_MESSAGES}" != "0" && "${sent}" -ge "${TOTAL_MESSAGES}" ]]; then
    break
  fi

  : > "${batch_file}"
  batch_count=0

  for ((i = 0; i < RATE_PER_SEC; i++)); do
    if [[ "${TOTAL_MESSAGES}" != "0" && "${sent}" -ge "${TOTAL_MESSAGES}" ]]; then
      break
    fi

    if [[ "${MODE}" == "synthetic" ]]; then
      build_synthetic_message "${sent}" >> "${batch_file}"
    else
      build_replay_message "${sent}" >> "${batch_file}"
    fi

    sent=$((sent + 1))
    batch_count=$((batch_count + 1))
  done

  if [[ "${batch_count}" -gt 0 ]]; then
    "${COMPOSE_CMD[@]}" exec -T kafka kafka-console-producer --bootstrap-server "${BOOTSTRAP_SERVER}" --topic "${TOPIC}" < "${batch_file}" >/dev/null
  fi

  echo "[loadgen] sent=${sent} elapsed=${elapsed}s"
  sleep 1

done

end_epoch="$(date +%s)"
actual_elapsed="$((end_epoch - start_epoch))"
if [[ "${actual_elapsed}" -lt 1 ]]; then
  actual_elapsed=1
fi

effective_rate="$((sent / actual_elapsed))"
echo "[loadgen] complete: sent=${sent}, elapsed=${actual_elapsed}s, effective_rate=${effective_rate}/s"
