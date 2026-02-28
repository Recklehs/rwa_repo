#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLLECTION="$SCRIPT_DIR/CryptoOrder-Custody-WalletCreate-E2E.postman_collection.json"
ENVIRONMENT="$SCRIPT_DIR/CryptoOrder-Custody-WalletCreate-E2E.postman_environment.json"

if ! command -v newman >/dev/null 2>&1; then
  echo "newman 이 설치되어 있지 않습니다."
  echo "설치: npm i -g newman"
  exit 1
fi

newman run "$COLLECTION" -e "$ENVIRONMENT" --reporters cli
