#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$SCRIPT_DIR/bin:$PATH"

echo "[setup] checking newman wrapper"
if newman -v >/dev/null 2>&1; then
  echo "[setup] newman ready"
else
  echo "[setup] newman check failed (network/npm may be unavailable)" >&2
fi

echo "[setup] checking k6 wrapper"
if k6 version >/dev/null 2>&1; then
  echo "[setup] k6 ready"
else
  echo "[setup] k6 check failed (docker pull/network may be unavailable)" >&2
fi

cat <<'NEXT'
[setup] done
- If one or both checks failed, run again in a network-enabled environment.
- The runner uses local wrappers at server/scripts/e2e/bin by default.
NEXT
