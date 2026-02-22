#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}" 
IDEMP_PREFIX="${IDEMP_PREFIX:-smoke-$(date +%s)}"
BUY_LISTING_ID="${BUY_LISTING_ID:-}"
SMOKE_KAPT_CODE="${SMOKE_KAPT_CODE:-SMOKE-KAPT-$(date +%s)}"
SHARE_SCALE="${SHARE_SCALE:-1000000000000000000}"
MUSD_FAUCET_AMOUNT="${MUSD_FAUCET_AMOUNT:-}"
MUSD_FAUCET_AMOUNT_HUMAN="${MUSD_FAUCET_AMOUNT_HUMAN:-1000}"
UNIT_PRICE="${UNIT_PRICE:-100000000000000000000}"
POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-3}"
POLL_MAX="${POLL_MAX:-120}"
SELLER_EXTERNAL_USER_ID="${SELLER_EXTERNAL_USER_ID:-$IDEMP_PREFIX-seller}"
BUYER_EXTERNAL_USER_ID="${BUYER_EXTERNAL_USER_ID:-$IDEMP_PREFIX-buyer}"

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required"
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required"
  exit 1
fi

if [[ -z "$ADMIN_TOKEN" ]]; then
  echo "ADMIN_TOKEN is required"
  exit 1
fi

echo "[smoke] BASE_URL=$BASE_URL"

auth_header() {
  local key="$1"
  printf -- "-HIdempotency-Key:%s" "$key"
}

post_json() {
  local path="$1"
  local body="$2"
  local key="$3"
  local include_admin="${4:-false}"

  if [[ "$include_admin" == "true" ]]; then
    curl -sS -X POST "$BASE_URL$path" \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: $key" \
      -H "X-Admin-Token: $ADMIN_TOKEN" \
      -d "$body"
  else
    curl -sS -X POST "$BASE_URL$path" \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: $key" \
      -d "$body"
  fi
}

get_json() {
  local path="$1"
  local include_admin="${2:-false}"
  if [[ "$include_admin" == "true" ]]; then
    curl -sS "$BASE_URL$path" -H "X-Admin-Token: $ADMIN_TOKEN"
  else
    curl -sS "$BASE_URL$path"
  fi
}

wait_outbox_mined() {
  local outbox_id="$1"
  local i=0
  while (( i < POLL_MAX )); do
    local tx
    tx="$(get_json "/tx/outbox/$outbox_id")"
    local status
    status="$(echo "$tx" | jq -r '.status')"
    local tx_hash
    tx_hash="$(echo "$tx" | jq -r '.txHash // ""')"
    echo "[smoke] outbox=$outbox_id status=$status txHash=$tx_hash"

    if [[ "$status" == "MINED" ]]; then
      return 0
    fi
    if [[ "$status" == "FAILED" ]]; then
      echo "[smoke] tx failed: $tx"
      return 1
    fi

    sleep "$POLL_INTERVAL_SEC"
    ((i+=1))
  done

  echo "[smoke] timeout waiting outbox=$outbox_id"
  return 1
}

assert_not_error_response() {
  local response="$1"
  local step="$2"

  local status
  status="$(echo "$response" | jq -r 'if type == "object" and has("status") and (.status | type) == "number" then .status else "" end' 2>/dev/null || true)"
  if [[ -n "$status" && "$status" =~ ^[0-9]+$ && "$status" -ge 400 ]]; then
    local message
    message="$(echo "$response" | jq -r '.message // ""' 2>/dev/null || true)"
    echo "[smoke] $step failed status=$status message=$message"
    echo "[smoke] response=$response"
    exit 1
  fi
}

wait_listing_id() {
  local class_id="$1"
  local i=0
  while (( i < POLL_MAX )); do
    local listings
    listings="$(get_json "/market/listings?classId=$class_id&status=ACTIVE")"
    local listing_id
    listing_id="$(echo "$listings" | jq -r '.[0].id // ""' 2>/dev/null || true)"
    echo "[smoke] listing lookup classId=$class_id listingId=$listing_id" >&2

    if [[ -n "$listing_id" && "$listing_id" != "null" ]]; then
      echo "$listing_id"
      return 0
    fi

    sleep "$POLL_INTERVAL_SEC"
    ((i+=1))
  done

  echo "[smoke] timeout waiting listingId for classId=$class_id" >&2
  return 1
}

# health check
health="$(get_json "/actuator/health")"
echo "[smoke] health=$health"

# 1) signup seller
seller_signup="$(post_json "/auth/signup" "{\"externalUserId\":\"$SELLER_EXTERNAL_USER_ID\"}" "$IDEMP_PREFIX-auth-seller")"
assert_not_error_response "$seller_signup" "signup seller"
seller_user_id="$(echo "$seller_signup" | jq -r '.userId')"
seller_address="$(echo "$seller_signup" | jq -r '.address')"
if [[ -z "$seller_user_id" || "$seller_user_id" == "null" || -z "$seller_address" || "$seller_address" == "null" ]]; then
  echo "[smoke] signup seller invalid response: $seller_signup"
  exit 1
fi
echo "[smoke] seller userId=$seller_user_id address=$seller_address"

# 2) signup buyer
buyer_signup="$(post_json "/auth/signup" "{\"externalUserId\":\"$BUYER_EXTERNAL_USER_ID\"}" "$IDEMP_PREFIX-auth-buyer")"
assert_not_error_response "$buyer_signup" "signup buyer"
buyer_user_id="$(echo "$buyer_signup" | jq -r '.userId')"
buyer_address="$(echo "$buyer_signup" | jq -r '.address')"
if [[ -z "$buyer_user_id" || "$buyer_user_id" == "null" || -z "$buyer_address" || "$buyer_address" == "null" ]]; then
  echo "[smoke] signup buyer invalid response: $buyer_signup"
  exit 1
fi
echo "[smoke] buyer userId=$buyer_user_id address=$buyer_address"

# 3) compliance approve
approve_seller="$(post_json "/admin/compliance/approve" "{\"userId\":\"$seller_user_id\"}" "$IDEMP_PREFIX-approve-seller" true)"
approve_buyer="$(post_json "/admin/compliance/approve" "{\"userId\":\"$buyer_user_id\"}" "$IDEMP_PREFIX-approve-buyer" true)"
assert_not_error_response "$approve_seller" "approve seller"
assert_not_error_response "$approve_buyer" "approve buyer"
echo "[smoke] approve seller=$approve_seller"
echo "[smoke] approve buyer=$approve_buyer"

# 4) complex import
complex_payload='{
  "rawItemJson": {
    "kaptCode": "__SMOKE_KAPT_CODE__",
    "kaptName": "Smoke Apartment",
    "kaptAddr": "Seoul Sample",
    "doroJuso": "Smoke-ro 1",
    "hoCnt": 2,
    "kaptMparea60": 1,
    "kaptMparea85": 1,
    "kaptMparea135": 0,
    "kaptMparea136": 0
  }
}'
complex_payload="${complex_payload/__SMOKE_KAPT_CODE__/$SMOKE_KAPT_CODE}"
import_res="$(post_json "/admin/complexes/import" "$complex_payload" "$IDEMP_PREFIX-import" true)"
assert_not_error_response "$import_res" "complex import"
echo "[smoke] import=$import_res"

classes="$(get_json "/complexes/$SMOKE_KAPT_CODE/classes")"
class_id="$(echo "$classes" | jq -r '.[0].classId')"
if [[ -z "$class_id" || "$class_id" == "null" ]]; then
  echo "[smoke] classId not found"
  exit 1
fi
echo "[smoke] classId=$class_id"

# 5) tokenize
# NOTE: tokenization may take multiple txs; wait for first reserve tx at least.
tokenize_res="$(post_json "/admin/classes/$class_id/tokenize" '{"chunkSize":100}' "$IDEMP_PREFIX-tokenize" true)"
assert_not_error_response "$tokenize_res" "tokenize"
echo "[smoke] tokenize=$tokenize_res"
first_tokenize_outbox="$(echo "$tokenize_res" | jq -r '.outboxIds[0]')"
if [[ -n "$first_tokenize_outbox" && "$first_tokenize_outbox" != "null" ]]; then
  wait_outbox_mined "$first_tokenize_outbox"
fi

units="$(get_json "/classes/$class_id/units")"
unit_id="$(echo "$units" | jq -r '.[0].unitId')"
if [[ -z "$unit_id" || "$unit_id" == "null" ]]; then
  echo "[smoke] unitId not found"
  exit 1
fi
echo "[smoke] unitId=$unit_id"

# 6) faucet buyer musd
if [[ -n "$MUSD_FAUCET_AMOUNT" ]]; then
  faucet_body="{\"toUserId\":\"$buyer_user_id\",\"amount\":$MUSD_FAUCET_AMOUNT}"
else
  faucet_body="{\"toUserId\":\"$buyer_user_id\",\"amountHuman\":$MUSD_FAUCET_AMOUNT_HUMAN}"
fi
faucet_res="$(post_json "/admin/faucet/musd" "$faucet_body" "$IDEMP_PREFIX-faucet" true)"
assert_not_error_response "$faucet_res" "faucet musd"
faucet_outbox="$(echo "$faucet_res" | jq -r '.outboxId')"
echo "[smoke] faucet=$faucet_res"
wait_outbox_mined "$faucet_outbox"

# 7) distribute one full unit share to seller
dist_res="$(post_json "/admin/distribute/shares" "{\"toUserId\":\"$seller_user_id\",\"unitId\":\"$unit_id\",\"amount\":$SHARE_SCALE}" "$IDEMP_PREFIX-distribute" true)"
assert_not_error_response "$dist_res" "distribute shares"
dist_outbox="$(echo "$dist_res" | jq -r '.outboxId')"
echo "[smoke] distribute=$dist_res"
wait_outbox_mined "$dist_outbox"

# 8) seller list
list_res="$(post_json "/trade/list" "{\"sellerUserId\":\"$seller_user_id\",\"unitId\":\"$unit_id\",\"amount\":$SHARE_SCALE,\"unitPrice\":$UNIT_PRICE}" "$IDEMP_PREFIX-list")"
assert_not_error_response "$list_res" "trade list"
list_outbox="$(echo "$list_res" | jq -r '.outboxIds[-1]')"
echo "[smoke] list=$list_res"
wait_outbox_mined "$list_outbox"

# 9) buyer buy (listingId 미지정 시 read-model에서 자동 탐색)
resolved_listing_id="$BUY_LISTING_ID"
if [[ -z "$resolved_listing_id" ]]; then
  resolved_listing_id="$(wait_listing_id "$class_id")"
fi
if [[ -z "$resolved_listing_id" || "$resolved_listing_id" == "null" ]]; then
  echo "[smoke] listingId not found. set BUY_LISTING_ID manually."
  exit 1
fi
echo "[smoke] using listingId=$resolved_listing_id"

buy_res="$(post_json "/trade/buy" "{\"buyerUserId\":\"$buyer_user_id\",\"listingId\":$resolved_listing_id,\"amount\":$SHARE_SCALE}" "$IDEMP_PREFIX-buy")"
assert_not_error_response "$buy_res" "trade buy"
buy_outbox="$(echo "$buy_res" | jq -r '.outboxIds[-1]')"
echo "[smoke] buy=$buy_res"
wait_outbox_mined "$buy_outbox"

echo "[smoke] DONE"
echo "[smoke] sellerUserId=$seller_user_id buyerUserId=$buyer_user_id classId=$class_id unitId=$unit_id listingId=$resolved_listing_id"
