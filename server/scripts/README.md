# Server Smoke E2E

Run a minimal API smoke flow for the custodial server:

1. `POST /auth/signup` (seller, buyer)
2. `POST /admin/compliance/approve` (seller, buyer)
3. `POST /admin/complexes/import`
4. `POST /admin/classes/{classId}/tokenize`
5. `POST /admin/faucet/musd`
6. `POST /admin/distribute/shares`
7. `POST /trade/list`
8. `POST /trade/buy`

## Prerequisites
- server is running (`http://localhost:8080` by default)
- `ADMIN_API_TOKEN` configured on server
- `curl`, `jq` installed

## Usage

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/server
BASE_URL="http://localhost:8080" \
ADMIN_TOKEN="<admin-token>" \
./scripts/smoke_e2e.sh
```

## Notes
- 모든 mutating API에 `Idempotency-Key`를 자동으로 부여합니다.
- `/auth/signup` 요청에는 `externalUserId`를 포함합니다 (`SELLER_EXTERNAL_USER_ID`, `BUYER_EXTERNAL_USER_ID` 환경변수로 override 가능).
- 체인 tx는 `/tx/outbox/{outboxId}`를 폴링해 `MINED`까지 대기합니다.
- `BUY_LISTING_ID`를 지정하지 않으면 `/market/listings?classId=...&status=ACTIVE`에서 자동으로 탐색합니다.
- 필요하면 `SMOKE_KAPT_CODE`를 지정해 테스트용 단지 코드를 고정할 수 있습니다.
- faucet 금액은 기본적으로 사람 단위(`MUSD_FAUCET_AMOUNT_HUMAN=1000`)로 전송합니다. raw 단위를 쓰려면 `MUSD_FAUCET_AMOUNT`를 지정하세요.

## Postman
- Collection: `/Users/kanghyoseung/Desktop/project/rwa/server/scripts/postman/rwa-server-e2e.postman_collection.json`
- Environment: `/Users/kanghyoseung/Desktop/project/rwa/server/scripts/postman/rwa-server-local.postman_environment.json`
- Addon (single request only): `/Users/kanghyoseung/Desktop/project/rwa/server/scripts/postman/rwa-server-addon-issued-tokens.postman_collection.json`
- Import 후 `adminToken`만 채우고 Collection Runner에서 순서대로 실행하면 E2E 흐름(가입→컴플라이언스→토큰화→거래→조회)을 재현할 수 있습니다.
- 기존 응답 히스토리를 보존하려면 전체 컬렉션 재-Import 대신 Addon을 Import한 뒤, `Issued Tokens (All)` 요청만 기존 컬렉션으로 드래그해서 복사하세요.
