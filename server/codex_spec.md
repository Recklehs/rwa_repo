# CODEX SPEC 2 - MERGED v4.1 (Baseline: 2026-02-15 post-live deploy)

Title: Spring Boot Custodial Ops Server (Off-chain compliance) + TxOrchestrator + CDC-friendly Transactional Outbox
+ API Idempotency + Distributed Nonce Lock + On-chain listing fallback

## 0) Baseline alignment (MUST)

### A) Compliance model
- On-chain allowlist enforcement is REMOVED.
- Do NOT reference legacy allowlist contracts anywhere.
- Backend must enforce compliance OFF-CHAIN before allowing user actions.
- Contracts do not block transfers/listing/buying via on-chain allowlist.

### B) Deployed contracts set (EXACTLY 5)
1. MockUSD
2. PropertyRegistry
3. PropertyShare1155
4. PropertyTokenizer
5. FixedPriceMarketDvP

### C) Ownership state (post-bootstrap, confirmed)
- MockUSD.owner = ISSUER_ADDRESS
- PropertyTokenizer.owner = ISSUER_ADDRESS
- PropertyRegistry.owner = PropertyTokenizer (contract)
- PropertyShare1155.owner = PropertyTokenizer (contract)
- Tokenization MUST be executed through PropertyTokenizer by ISSUER key.

### D) Runtime source of truth (MUST)
- Contract addresses: `shared/deployments/giwa-sepolia.json`
- Contract ABIs: `shared/abi/*.json` (no legacy allowlist ABI)
- Constants: `shared/config/constants.json`
  - `SHARE_SCALE = 1e18`
  - `CONFIRMATIONS = 12`
- Do NOT hardcode deployed addresses in code.

### E) Network config
- `chainId=91342`, `RPC=https://sepolia-rpc.giwa.io` (GIWA Sepolia)
- Nonce source: `eth_getTransactionCount(from, "pending")`

## 1) Architecture / Responsibilities

### Server responsibilities
- Custodial wallet issuance and secure key storage
- Off-chain compliance approval/revocation + enforcement gates
- Public data import (complex/class/unit creation) including UNKNOWN class (Option A)
- Tokenization orchestration via PropertyTokenizer (reserve+register+mint chunking, mined 확인 기반 단계 진행)
- Tokenization 결과의 on-chain registry 재조회 기반 동기화(`docHash/baseTokenId/issuedAt/status`)
- Faucet mUSD and share distribution
- Trading orchestration (list/buy) as user-signed txs (custodial)
- Tx Orchestrator (nonce safety, outbox_tx, receipts, retries)
- Read-only query APIs powered by Postgres read-models updated by Flink (listings/trades/balances)
- Public token catalog query (`GET /tokens`) and registry introspection (`GET /admin/classes/{classId}/registry`)
- Transactional Outbox Pattern (CDC-friendly) for domain events -> Kafka (optional downstream consumers)
- API Idempotency for ALL mutating endpoints
- Distributed per-address nonce lock for multi-instance safety

### Indexing responsibilities (NOT in this server)
- On-chain events -> Kafka -> Flink -> DB read models handled by Ingester/Flink modules.

## 2) Tech stack (recommended)
- Java 21 + Spring Boot 3.x (project runtime currently Java 17)
- Spring Web, Validation
- Spring Data JPA (Hibernate)
- Postgres 15+
- Web3j
- Spring Kafka
- Flyway
- AES-GCM
- @Async + ThreadPoolTaskExecutor
- @Scheduled for retry jobs

## 3) Database schema (minimum; Flyway)

### A) Custodial + compliance
- `users(user_id uuid PK default uuidv7(), created_at timestamptz, compliance_status text, compliance_updated_at timestamptz)`
- `wallets(user_id uuid PK FK users, address varchar(42) unique, encrypted_privkey bytea, enc_version int, created_at timestamptz)`
- `user_external_links(id bigserial PK, provider text, external_user_id text, user_id uuid FK users, created_at timestamptz, updated_at timestamptz, UNIQUE(provider, external_user_id), UNIQUE(provider, user_id))`

### B) Public data store
- `complexes(kapt_code PK, kapt_name, kapt_addr, doro_juso, ho_cnt, raw_json jsonb, fetched_at timestamptz)`
- `classes(class_id PK hex bytes32, kapt_code FK, class_key, unit_count, status, doc_hash, base_token_id numeric(78,0), issued_at, created_at, updated_at)`
- `units(unit_id PK, class_id FK, unit_no int, token_id numeric(78,0), status, UNIQUE(class_id, unit_no))`

### C) Tx Orchestrator outbox
- `outbox_tx(outbox_id uuid PK, request_id text UNIQUE NOT NULL, from_address varchar(42) NOT NULL, to_address varchar(42), nonce bigint, tx_hash varchar(66), raw_tx text, status text NOT NULL, tx_type text NOT NULL, payload jsonb, last_error text, created_at timestamptz default now(), updated_at timestamptz default now())`

### D) Read model (server reads only)
- `processed_events(id bigserial PK, event_key text, block_number bigint, tx_hash text, payload jsonb, created_at timestamptz)`
- `listings(id bigserial PK, property_id text, listing_status text, price numeric, created_at timestamptz, updated_at timestamptz)`
- `trades(id bigserial PK, listing_id bigint, buyer text, seller text, tx_hash text, traded_at timestamptz, amount numeric)`
- `balances(owner text PK, token_id bigint, amount numeric, updated_at timestamptz)`

### E) API Idempotency table
- `api_idempotency(endpoint text NOT NULL, idempotency_key text NOT NULL, request_hash varchar(64) NOT NULL, status text NOT NULL, response_status int, response_body jsonb, created_at timestamptz not null default now(), updated_at timestamptz not null default now(), primary key(endpoint, idempotency_key))`
- Index `(status, created_at)`

### Idempotency rules
- All mutating endpoints require `Idempotency-Key`.
- Missing key -> `400 "Idempotency-Key required"`.
- Same key + different payload hash -> `409 "Idempotency-Key reuse with different payload"`.
- `COMPLETED` -> return stored status/body.
- `IN_PROGRESS` -> return `202` (status endpoint pointer).
- Success -> update `COMPLETED`.
- Failure -> update `FAILED` with minimal error payload.

## 4) CDC-friendly Transactional Outbox

### Tables
- `outbox_event` (INSERT-ONLY)
- `outbox_delivery` (mutable operational state)

### DomainEvent required fields
- `eventId`, `aggregateType`, `aggregateId`, `eventType`, `occurredAt`, `topic`, `partitionKey`, `payload`

### Publishing flow
1. `@Transactional` domain service publishes `DomainEvent`.
2. `@TransactionalEventListener(BEFORE_COMMIT)` persists `outbox_event + outbox_delivery(INIT)`.
3. `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` publishes to Kafka.
   - success: `SEND_SUCCESS`
   - fail: `SEND_FAIL`, increment attempts, retry metadata update

### Retry scheduler rules
- CLAIM -> SEND -> COMPLETE pattern
- Must NOT hold DB locks during Kafka send
- reclaim expired locks via TTL
- config keys:
  - `OUTBOX_RETRY_MIN_AGE_MINUTES=10`
  - `OUTBOX_MAX_ATTEMPTS=30`
  - `OUTBOX_RETRY_INTERVAL_MS=60000`
  - `OUTBOX_LOCK_TTL_SECONDS=300`
  - `OUTBOX_CLAIM_BATCH_SIZE=100`

### Graceful shutdown
- outbox publisher executor:
  - `setWaitForTasksToCompleteOnShutdown(true)`
  - `setAwaitTerminationSeconds(10)`

## 5) Distributed per-address nonce lock (MUST)
- Multi-instance safe lock required.
- Postgres advisory lock:
  - acquire: `SELECT pg_advisory_lock(hashtext(lower(:from_address)));`
  - release: `SELECT pg_advisory_unlock(hashtext(lower(:from_address)));`
- Lock coverage:
  1. pending nonce read
  2. signing
  3. send to RPC
  4. outbox_tx persist/update
- Same DB connection for lock/unlock.
- Timeout (`NONCE_LOCK_TIMEOUT_MS`, default 5000ms) -> `409/429 "Address is busy"`.

## 6) Shared runtime config / secrets
- `GIWA_RPC_URL=https://sepolia-rpc.giwa.io`
- `GIWA_CHAIN_ID=91342`
- `SHARED_DIR_PATH=../shared`
- `MASTER_KEY_BASE64=...`
- `ISSUER_PRIVATE_KEY=...`
- `TREASURY_PRIVATE_KEY=...`
- `KAFKA_BOOTSTRAP_SERVERS=...`
- `OUTBOX_DEFAULT_TOPIC=server.domain.events`
- `NONCE_LOCK_TIMEOUT_MS=5000`
- `Idempotency-Key` required on all mutating endpoints
- `SERVICE_TOKEN=...`, `SERVICE_TOKEN_HEADER=X-Service-Token`, `INTERNAL_PATHS=/internal/**`
- `AUTH_MODE=JWKS|HMAC|LOCAL`
- `AUTH_JWKS_URL=...`, `AUTH_JWT_ISSUER=...`, `AUTH_JWT_AUDIENCE=...`, `AUTH_CLOCK_SKEW_SECONDS=...`
- `AUTH_REQUIRED_PATHS=...` (baseline 보호 경로 `/trade/**`, `/me/**`, `/wallet/**`, `/tx/**`, `/users/**/holdings`는 항상 강제)
- `AUTH_JWKS_CACHE_SECONDS=...`, `AUTH_HMAC_SECRET_BASE64=...`, `LOCAL_AUTH_ENABLED=true|false`

## 7) Core REST APIs (minimum)
- `POST /auth/signup_local` (LOCAL mode + `LOCAL_AUTH_ENABLED=true` only; body: `externalUserId` required, `provider` optional default `MEMBER`; response includes `accessToken/tokenType/expiresInSeconds`)
- `GET /admin/users/by-external?externalUserId=...&provider=...`
- `POST /admin/compliance/approve`
- `POST /admin/compliance/revoke`
- `GET /admin/compliance/users`
- `POST /internal/wallets/provision` (`X-Service-Token` + `Idempotency-Key` required)
- `POST /admin/complexes/import`
- `GET /complexes`
- `GET /complexes/{kaptCode}`
- `GET /complexes/{kaptCode}/classes`
- `GET /classes/{classId}/units`
- `GET /tokens` (DB 기준 발행 완료 token/unit 목록)
- `POST /admin/classes/{classId}/tokenize`
- `GET /admin/classes/{classId}/registry`
- `POST /admin/faucet/musd` (body: `toUserId` + (`amount` raw or `amountHuman`))
- `POST /admin/wallets/credit/musd` (body: `toUserId` + (`amount` raw or `amountHuman`) + `mode`=`MINT|TRANSFER`)
- `POST /admin/distribute/shares`
- `POST /trade/list`
- `POST /trade/buy`
- `POST /trade/cancel`
- `GET /me`
- `GET /me/wallet`
- `GET /me/orders`
- `GET /me/trades`
- `POST /wallet/transfer/musd`
- `GET /tx/outbox/{outboxId}` (Bearer required, principal-owned tx only)
- `GET /tx/by-hash/{txHash}` (Bearer required, principal-owned tx only)
- `GET /market/listings`
- `GET /users/{userId}/holdings` (Bearer required, path userId must match JWT sub)
- `GET /tokens/{tokenId}/holders`
- `GET /admin/indexer/status`
- `GET /system/data-freshness`

## 8) Tx Orchestrator (MUST)
- Must apply distributed address lock.
- Must use pending nonce.
- `submitContractTx()` is executed in independent transaction boundary (`REQUIRES_NEW`) so FAILED traces persist.
- `outbox_tx` lifecycle: `CREATED -> SIGNED -> SENT -> MINED | FAILED | REPLACED`
- Chain final business state is from Flink read-model events, not receipt-only.

## 9) Acceptance criteria
- Idempotency correctness (same key same payload = same response; no duplicates)
- Same key different payload => 409
- Two instances same sender do not collide nonce
- Retry scheduler does not hold DB lock while sending Kafka
- Listing lag scenario still allows `/trade/buy` via on-chain listing fallback

## 10) JUnit test coverage (updated: 2026-02-23)
- Test execution command: `cd server && ./gradlew test`
- Latest rerun result: `55 tests, 0 failures, 0 errors, 1 ignored`

### Unit tests
- `server/src/test/java/io/rwa/server/wallet/WalletServiceTest.java`
  - `signup(externalUserId, provider)` persists `users/wallets/user_external_links`, encrypts private key, publishes `UserSignedUp` outbox event payload.
  - Existing external mapping returns existing `userId/address` without re-creating wallet.
  - SQLState 기반 `RETURNING` fallback 분기(호환 케이스만 fallback, non-compat grammar는 fail-fast) 검증.
  - `assertApproved()` rejects non-approved users with `403`.
  - `getWallet()` returns `404` when wallet row is missing.
- `server/src/test/java/io/rwa/server/trade/TradeServiceTest.java`
  - `list()` submits approval tx first when `isApprovedForAll=false`, then submits list tx.
  - `list()` rejects missing `tokenId` and `unitId` with `400`.
  - `buy()` rejects non-ACTIVE listing with `409`.
  - `buy()` submits approve+buy tx sequence when allowance is insufficient and validates cost calculation.
  - `list()/buy()/cancel()` validates positive numeric inputs (`listingId/amount/unitPrice > 0`).
- `server/src/test/java/io/rwa/server/trade/AdminAssetServiceTest.java`
  - `/admin/faucet/musd`에서 `amountHuman`(사람 단위) -> raw(18 decimals) 변환 검증.
  - 기존 `amount`(raw) 입력과의 하위 호환 검증.
  - `amount`+`amountHuman` 동시 입력 시 `400` 검증.
  - `amountHuman` 소수점 18자리 초과 시 `400` 검증.
- `server/src/test/java/io/rwa/server/security/UserAuthFilterJwtModesTest.java`
  - AUTH_MODE=JWKS/HMAC 토큰 검증(서명/iss/aud/exp), 보호 경로 Bearer 강제, JWKS key-rotation refresh 검증.
- `server/src/test/java/io/rwa/server/wallet/WalletServiceTest.java`
  - `provisionWallet()` 외부 링크 충돌 시 `409` + 지갑 생성/가스지급 미실행 검증.

### Web integration tests (MockMvc)
- `server/src/test/java/io/rwa/server/auth/AuthIdempotencyIntegrationTest.java`
  - `/auth/signup_local` without `Idempotency-Key` returns `400`.
  - First request with key stores completed idempotency response (including `externalUserId/provider/created`).
  - Repeated request with same key returns stored response (replay) and skips `walletService.signup()`.
- `server/src/test/java/io/rwa/server/auth/AuthSignupDbDefaultIntegrationTest.java`
  - Verifies `/auth/signup_local` persists `users/wallets/user_external_links` with DB-generated `users.user_id` (uuid v7).
  - Verifies returned `userId` is UUID v7 and persisted user compliance defaults are `PENDING`.
  - Runs only when `-DrunPg18Integration=true` (PG18 integration gate).
- `server/src/test/java/io/rwa/server/auth/AdminUserQueryControllerTest.java`
  - `/admin/users/by-external` without `X-Admin-Token` returns `401`.
  - Same endpoint with valid token returns linked `userId/address` payload.
- `server/src/test/java/io/rwa/server/compliance/ComplianceAdminTokenIntegrationTest.java`
  - `/admin/compliance/users` without `X-Admin-Token` returns `401`.
  - Same endpoint with valid token returns `200` and mapped user compliance payload.
- `server/src/test/java/io/rwa/server/internal/InternalWalletProvisionControllerTest.java`
  - `/internal/wallets/provision` service token/idempotency 필수 및 idempotent behavior 검증.

### JPA/Hibernate integration tests (`@DataJpaTest` + `@Transactional`)
- Test DB mode: H2 in-memory with DDL auto create/drop and JSONB domain alias for entity compatibility.
- Rollback policy: each test method runs in transaction and rolls back automatically on completion.
- `server/src/test/java/io/rwa/server/wallet/UserWalletRepositoryJpaIntegrationTest.java`
  - `UserRepository.findByComplianceStatus` 조회 정확성 검증.
  - `WalletRepository.findByAddress` 저장/조회 매핑 검증.
  - `wallets.address` unique 제약 위반 시 예외 발생 검증.
- `server/src/test/java/io/rwa/server/publicdata/PublicDataRepositoryJpaIntegrationTest.java`
  - `UnitRepository.findByClassIdOrderByUnitNoAsc` 정렬 조회 검증.
  - `UnitRepository.findByClassIdAndUnitNo` 단건 조회 검증.
  - `UnitRepository.findByTokenIdIsNotNullOrderByTokenIdAsc` 발행 토큰 필터/정렬 조회 검증.
  - `PropertyClassRepository.findByKaptCodeOrderByClassKeyAsc` 정렬 조회 검증.

## Implementation Notes (project decisions)
- Admin auth mode: `X-Admin-Token` header
- Deterministic IDs:
  - classId:
    - default: `keccak256(lower(kaptCode)+":"+classKey)`
    - compatibility rule for migrated public buckets:
      - `MPAREA_LE_60 -> hash basis MPAREA_60`
      - `MPAREA_60_85 -> hash basis MPAREA_85`
      - `MPAREA_85_135 -> hash basis MPAREA_135`
      - `MPAREA_GE_136 -> hash basis MPAREA_136`

## CODEX FEATURE SPEC - Server Add-on v4.2 (revA: External Identity + Auth Split)

Baseline: SPEC2 MERGED v4.1 / 2026-02-22

### 0) Baseline invariants (MUST)
- On-chain allowlist/KYC enforcement 없음(레거시 allowlist ABI/주소 참조 금지)
- Deployed contracts EXACTLY 5개: MockUSD, PropertyRegistry, PropertyShare1155, PropertyTokenizer, FixedPriceMarketDvP
- 주소/ABI/상수는 런타임에 shared/*에서 로딩 (하드코딩 금지)
- CONFIRMATIONS=12, SHARE_SCALE=1e18는 shared/config/constants.json에서 로딩
- Nonce source: eth_getTransactionCount(from, "pending")
- 모든 POST/PUT/PATCH/DELETE는 Idempotency-Key 필수
- 모든 /admin/**는 X-Admin-Token 필수
- 포트 8080
- TxOrchestrator / distributed nonce lock / outbox 정책 v4.1 그대로 유지

### 1) 권한/인증 모델 개편 (회원 서버 분리)
- 회원 서버가 accessToken(JWT)을 발급하고, 커스터디 서버는 accessToken 검증만 수행
- Refresh Token은 커스터디 서버에서 취급하지 않음
- 공개 /auth/signup, /auth/login은 제거 원칙
- LOCAL 개발 모드에서는 /auth/signup_local 사용 가능

#### 1-B. 토큰 검증 방식 (MUST: JWKS 우선)
- AUTH_MODE: JWKS|HMAC|LOCAL
- JWKS 모드: AUTH_JWKS_URL 공개키 기반 RS256/ES256 서명 검증
- 공통 검증: signature, exp/iat, iss, aud, clock skew
- AUTH_REQUIRED_PATHS 기반 Bearer 강제
- Baseline protected paths are always enforced: `/trade/**`, `/me/**`, `/wallet/**`, `/tx/**`, `/users/**/holdings`

#### 1-C. JWT claim
- 필수: sub(UUID), iss, iat, exp
- 권장: aud
- 선택: roles, provider, externalUserId
- addr claim은 신뢰하지 않고 DB(wallets) 조회 기준 사용

#### 1-D. 보안 필터
- UserAuthFilter: AUTH_REQUIRED_PATHS에서 Bearer JWT 검증 후 UserPrincipal 주입
- AdminTokenFilter: /admin/** 에서 X-Admin-Token 강제
- ServiceTokenFilter: /internal/** 에서 X-Service-Token 강제
- IdempotencyStatusController: `/idempotency/status` 는 X-Admin-Token 또는 X-Service-Token 필요 (responseBody 비노출)

#### 1-E. trade write API userId 정리
- /trade/list, /trade/buy의 sellerUserId/buyerUserId는 optional 호환 필드
- 포함 시 principal.userId 불일치면 403
- 실제 실행 주체는 항상 principal.userId

### 2) 회원 서버 ↔ 커스터디 지갑 Provisioning (MUST)
- 내부 동기 API: POST /internal/wallets/provision
- 헤더: X-Service-Token + Idempotency-Key 필수
- body: userId, provider, externalUserId
- 동작:
  - users 없으면 생성(PENDING)
  - wallets 없으면 생성(암호화 저장)
  - 있으면 기존 address 반환(멱등)

### 3) 사용자 프로필/지갑 조회
- GET /me: userId/address/complianceStatus/externalLinks[]
- GET /me/wallet: address, ethBalance, musdBalance, shareApprovalForMarket, musdAllowanceToMarket, complianceStatus
- wallet 미프로비저닝 시 404 WALLET_NOT_PROVISIONED

### 4) 거래 취소 + 주문/체결
- POST /trade/cancel: principal.userId + seller address 검증 + compliance gate + Market.cancel 제출(tx_type=CANCEL)
- user_orders write-model 유지
- GET /me/orders, GET /me/trades 제공

### 5) 인덱싱 상태/데이터 신선도
- GET /admin/indexer/status
- GET /system/data-freshness

### 6) 지갑/자금 관리
- POST /wallet/transfer/musd: recipient는 userId/externalUserId 기준
- POST /admin/wallets/credit/musd: mode=MINT|TRANSFER 지원

### 7) 에러 코드 표준
- 400 validation, 401 auth, 403 policy, 404 not found, 409 idempotency/conflict
- 202 idempotency in-progress replay

### 8) Tests (Codex MUST)
- JWT JWKS/HMAC 검증
- AUTH_REQUIRED_PATHS Bearer 누락 시 401
- /internal/wallets/provision: service token/idempotency/idempotent behavior
- v4.2 기존 테스트 회귀 방지

## v4.2 revA Implementation Status (updated: 2026-02-23)

- [x] AUTH_MODE(JWKS/HMAC/LOCAL), AUTH_* 환경변수, SERVICE_* 환경변수 바인딩 추가
- [x] UserAuthFilter + ServiceTokenFilter + AdminTokenFilter 분리 운영
- [x] JwtVerifier 인터페이스 및 JwksJwtVerifier/HmacJwtVerifier/LocalJwtIssuer 구현
- [x] 공개 /auth/signup 제거, /auth/signup_local(LOCAL 모드 전용) 추가
- [x] /internal/wallets/provision 구현 및 지갑 프로비저닝 멱등 처리
- [x] /me, /me/wallet, /me/orders, /me/trades 구현
- [x] /trade/list,/trade/buy principal 기반 전환 + userId mismatch 403 처리
- [x] /trade/cancel 구현(CANCEL tx type)
- [x] /wallet/transfer/musd 구현(userId/externalUserId recipient 제약)
- [x] /admin/wallets/credit/musd 구현(mode=MINT|TRANSFER)
- [x] /admin/indexer/status, /system/data-freshness 구현
- [x] user_orders 마이그레이션(V13__user_orders.sql) 및 write-model 기록 추가
- [x] JWT/JWKS/HMAC 및 internal provisioning 관련 테스트 추가/갱신
- [x] /tx/** JWT 보호 + principal 소유 tx만 조회 + requestId/lastError 비노출
- [x] /idempotency/status 관리자/서비스 토큰 제한 + responseBody 비노출
- [x] /users/{userId}/holdings 본인(userId==JWT sub) 강제
- [x] /auth/signup_local LOCAL 응답에 `accessToken/tokenType/expiresInSeconds` 포함
- [x] /wallet/transfer/musd recipient 식별자 XOR/self-transfer 금지/송수신 compliance gate 적용
- [x] `GlobalExceptionHandler` 400 표준화 (missing header/query param/malformed JSON)
- [x] 전체 테스트 통과 (`./gradlew test`)

### v4.2 Additional Contract Notes
- unitId: `{kaptCode}|{classKey}|{pad5(unitNo)}`
- Public classKey buckets (AGENTS global alignment):
  - `kaptMparea60 -> MPAREA_LE_60`
  - `kaptMparea85 -> MPAREA_60_85`
  - `kaptMparea135 -> MPAREA_85_135`
  - `kaptMparea136 -> MPAREA_GE_136`
  - `MPAREA_UNKNOWN` is used as delta bucket when `hoCnt` mismatch produces positive remainder.
- Signup request contract:
  - `POST /auth/signup_local` requires request body field `externalUserId` (LOCAL mode only).
  - `provider` is optional and defaults to `MEMBER`.
  - Existing `(provider, externalUserId)` mapping returns existing wallet (`created=false`).
  - Response includes `accessToken`, `tokenType=Bearer`, `expiresInSeconds`; non-LOCAL mode or disabled LOCAL auth returns `404`.
- User ID generation:
  - `users.user_id` is generated by DB default `uuidv7()`.
  - `signup()` first tries SQL `INSERT ... RETURNING user_id`.
  - Fallback to explicit app-generated UUIDv7 insert is allowed only for SQL syntax/feature-unsupported (`SQLState 42601 / 0A000`) compatibility around `RETURNING`.
  - Non-compat SQL grammar failures must fail fast with migration guidance (`V1~V10`).
- Kafka mode: feature flag (`outbox.kafka.enabled`)
- Java baseline: 17 (repository/runtime aligned)
- Faucet request contract:
  - `POST /admin/faucet/musd` accepts one of:
    - `amount` (raw, 10^18 scale)
    - `amountHuman` (human-readable decimal, server converts to raw)
  - Rejects requests that provide both fields or none.
- Tokenize contract behavior:
  - Reserve tx mined 확인 후 mint 단계 진행.
  - Mint tx mined 확인 후 `classes/units` 상태 반영.
  - Finalize 시 on-chain registry 재조회 값으로 `docHash/baseTokenId/issuedAt/status` 동기화.
- Public token catalog:
  - `GET /tokens` returns issued unit-token rows (`units.token_id IS NOT NULL`) with class metadata.
- Registry introspection:
  - `GET /admin/classes/{classId}/registry` returns current on-chain class registry state.

## Update Policy
- `server/codex_spec.md` is the server module's single source of truth for this request.
- Any server-side behavior change, schema change, endpoint contract change, or runtime policy change MUST update this file in the same change set.
- Change log entries must include date, scope, and impacted files/components.

## Change Log
- 2026-02-23: Synced codex spec with revA implementation details across auth/local signup, wallet transfer, internal provisioning, and test coverage.
  - Reflected runtime/env contract for `SERVICE_*` and `AUTH_*` settings (including baseline-auth path enforcement).
  - Expanded core REST list with `/internal/wallets/provision`, `/trade/cancel`, `/wallet/transfer/musd`, `/me/*`, `/admin/wallets/credit/musd`, `/admin/indexer/status`, `/system/data-freshness`.
  - Added `signup_local` LOCAL-token response contract (`accessToken/tokenType/expiresInSeconds`) and LOCAL-mode gating behavior.
  - Updated test coverage section to include new security/internal integration tests and latest rerun result (`55 tests, 0 failures, 0 errors, 1 ignored`).
  - Fixed misplaced markdown bullets in v4.2 notes (`MPAREA_GE_136` compatibility and additional contract notes grouping).
  - Impacted files: `codex_spec.md`, `AuthController`, `WalletTransferService`, `InternalWalletProvisionController`, `UserAuthFilterJwtModesTest`, `InternalWalletProvisionControllerTest`.
- 2026-02-23: Hardened auth/authorization boundaries for tx, idempotency status, and user holdings queries.
  - `/tx/**` now requires Bearer JWT and restricts reads to principal-owned tx only; removed `requestId`/`lastError` from response surface.
  - `/idempotency/status` now requires `X-Admin-Token` or `X-Service-Token` and no longer exposes stored `responseBody`.
  - `/users/{userId}/holdings` now requires Bearer JWT and enforces `path userId == JWT sub`.
  - Updated baseline auth-protected path documentation and v4.2 implementation checklist.
  - Impacted files: `SecurityPropertyResolver`, `TxQueryController`, `IdempotencyStatusController`, `ReadModelQueryController`, `codex_spec.md`.
- 2026-02-16: Initialized v4.1 server baseline spec in `server/codex_spec.md`.
  - Added implementation decisions fixed during planning (admin auth/header, deterministic IDs, Kafka feature flag, Java 17 baseline).
  - Marked outbox/nonce/idempotency/trading fallback requirements as hard constraints for server implementation.
- 2026-02-16: Implemented Spring Boot server baseline (MVP skeleton + core flows).
  - Added Flyway schema for write-model/outbox/idempotency.
  - Added shared deployment/ABI/constants runtime loaders (`shared/deployments/giwa-sepolia.json`, `shared/abi/*.json`, `shared/config/constants.json`).
  - Implemented global idempotency interceptor + status endpoint + cached response replay.
  - Implemented transactional outbox listeners (BEFORE_COMMIT persist, AFTER_COMMIT async publish) and retry scheduler (claim/send/complete).
  - Implemented Postgres advisory-lock nonce manager and `outbox_tx` orchestrator with receipt polling.
  - Implemented custodial wallet signup/encryption, compliance APIs, public-data import/query APIs, tokenize API, faucet/distribute APIs, trade list/buy APIs with on-chain listing fallback.
- 2026-02-16: Added execution tooling and smoke scenario.
  - Added Gradle Wrapper under `server/gradle/wrapper` and `server/gradlew*`.
  - Added smoke script `server/scripts/smoke_e2e.sh` and usage guide `server/scripts/README.md`.
  - Verified server module build/tests pass with wrapper (`./gradlew test`).
- 2026-02-17: Added `.env`-based server configuration workflow.
  - Enabled optional `.env` loading via `spring.config.import` in `server/src/main/resources/application.yml`.
  - Added template file `server/.env.example` for local runtime variable management.
  - Updated root `.gitignore` to explicitly ignore `server/.env` while allowing `server/.env.example`.
- 2026-02-17: Updated environment and local infra defaults for current setup.
  - Updated `server/.env` DB settings to Azure PostgreSQL endpoint (`giwapsqlserver.postgres.database.azure.com`) using provided administrator login credentials.
  - Updated local infra Postgres image to `postgres:17.7` in `infra/docker-compose.yml`.
- 2026-02-17: Replaced placeholder tests with JUnit unit/web integration coverage for live server behaviors.
  - Added `WalletServiceTest` and `TradeServiceTest` for core service logic and error branches.
  - Added `AuthIdempotencyIntegrationTest` and `ComplianceAdminTokenIntegrationTest` for interceptor/filter/controller integration via MockMvc.
  - Removed placeholder tests (`TradeBuyFallbackTest`, `IdempotencyIntegrationTest`, `OutboxRetrySchedulerTest`, `NonceLockConcurrencyTest`) and validated `./gradlew test` pass (`12/12`).
- 2026-02-17: Added JPA/Hibernate repository integration tests with transactional rollback.
  - Added H2 test runtime dependency in `server/build.gradle`.
  - Added `UserWalletRepositoryJpaIntegrationTest` and `PublicDataRepositoryJpaIntegrationTest` using `@DataJpaTest` + `@Transactional`.
  - Configured test datasource for JSONB-compatible entity DDL (`INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON`) and verified full test suite pass (`18/18`).
- 2026-02-17: Added migration SQL column/table usage comments for onboarding readability.
  - Updated `server/src/main/resources/db/migration/V1__core_write_model.sql`, `V2__tx_orchestrator.sql`, `V3__outbox_domain.sql`, `V4__api_idempotency.sql`.
  - Added table purpose descriptions and per-column meaning + concrete example value comments.
- 2026-02-17: Switched user ID generation to PostgreSQL 18 native UUID v7 defaults.
  - Updated schema to `users.user_id UUID PRIMARY KEY DEFAULT uuidv7()`.
  - Updated JPA mapping to `@GeneratedValue(strategy = GenerationType.IDENTITY)` on `UserEntity.userId`.
  - Updated signup flow to persist `UserEntity` first and consume DB-generated `user_id`.
  - Added `V6__users_uuidv7_default.sql` migration to enforce DB default on existing tables.
  - Added `AuthSignupDbDefaultIntegrationTest` (opt-in via `-DrunPg18Integration=true`) to verify legacy `/auth/signup` DB-default UUID behavior (revA 이후 `/auth/signup_local`로 이관).
- 2026-02-22: Fixed smoke/runtime issues found during end-to-end execution.
  - Updated `WalletService.signup()` to use SQL `INSERT ... RETURNING user_id` for deterministic DB-default UUID retrieval.
  - Added fallback path to explicit app-generated UUIDv7 insert when `RETURNING` query translation fails.
  - Removed `@GeneratedValue(strategy = GenerationType.IDENTITY)` from `UserEntity` to avoid UUID identity strategy mismatches.
  - Added JSONB write casting for JPA string fields: `ComplexEntity.rawJson`, `OutboxTxEntity.payload` (`?::jsonb`).
  - Improved `server/scripts/smoke_e2e.sh` with API-error fail-fast checks and automatic listingId discovery when `BUY_LISTING_ID` is omitted.
  - Updated smoke docs in `server/scripts/README.md` for new auto-listing behavior.
- 2026-02-22: Added external user linkage for signup and admin lookup APIs.
  - Added `V7__user_external_links.sql` and `user_external_links` mapping table (`provider + external_user_id -> user_id`).
  - Updated legacy `/auth/signup` contract to require `externalUserId` request body (`provider` optional, default `MEMBER`) (revA 이후 `/auth/signup_local`로 이관).
  - Updated wallet signup flow to reuse existing linked wallet for duplicate external IDs and return `created` flag.
  - Added `/admin/users/by-external` endpoint for admin-side user/address lookup by external identifier.
  - Updated smoke script and auth/wallet tests for new signup request/response contract.
- 2026-02-22: Added defensive schema reconciliation for legacy DBs.
  - Added `V8__users_schema_reconcile.sql` to backfill/repair `users` core columns when prior environments drifted.
  - Tightened signup fallback handling so non-`RETURNING` SQL grammar errors fail fast with migration guidance.
- 2026-02-22: Hardened outbox insert compatibility for legacy DB/runtime drift.
  - Updated outbox insert binding to pass `occurred_at` as explicit JDBC timestamp type (Instant inference-safe).
  - Removed hard cast `CAST(:payload AS jsonb)` in outbox insert to tolerate jsonb/text drift while preserving JSON payload.
  - Added `V9__outbox_schema_reconcile.sql` to defensively ensure `outbox_event/outbox_delivery` core columns/constraints/defaults exist.
- 2026-02-22: Completed server DB compatibility/drift audit and applied fixes.
  - Updated `IdempotencyService` to remove hard `CAST(:responseBody AS jsonb)` so `api_idempotency.response_body` writes tolerate jsonb/text drift.
  - Tightened `WalletService` `BadSqlGrammarException` fallback gating to SQLState-based `RETURNING` compatibility only, with explicit schema-mismatch errors for non-compat failures.
  - Added `V10__read_model_schema_bootstrap.sql` so read-model dependencies (`processed_events/listings/trades/balances`) exist before query endpoints are called.
- 2026-02-22: Fixed `/auth/signup` outbox insert SQL-grammar failures caused by payload type binding drift.
  - Note: above fix was applied before revA auth split and corresponds to legacy `/auth/signup` path (current LOCAL path is `/auth/signup_local`).
  - Updated `OutboxRepository.insertEventAndInitDelivery()` to detect `outbox_event.payload` column type from `information_schema` and choose SQL accordingly:
    - `jsonb` column: `CAST(:payload AS jsonb)`
    - non-`jsonb` column: plain `:payload` binding
  - Added `OutboxRepositoryTest` to validate SQL selection for both column type cases.
- 2026-02-22: Hardened tokenize finalize path and added token/registry query APIs.
  - Scope: tokenize orchestration reliability, public/admin query surface.
  - Tokenize now waits reserve mined before mint, waits mint mined before finalize, and syncs class metadata from on-chain registry.
  - Added `GET /admin/classes/{classId}/registry` for direct on-chain class state introspection.
  - Added `GET /tokens` to list all issued unit tokens (`token_id IS NOT NULL`) with class metadata.
  - Impacted files/components:
    - `server/src/main/java/io/rwa/server/tokenize/TokenizeService.java`
    - `server/src/main/java/io/rwa/server/tokenize/TokenizeController.java`
    - `server/src/main/java/io/rwa/server/publicdata/PublicDataController.java`
    - `server/src/main/java/io/rwa/server/publicdata/PublicDataService.java`
    - `server/src/main/java/io/rwa/server/publicdata/UnitRepository.java`
    - `server/src/main/java/io/rwa/server/publicdata/IssuedTokenItem.java`
    - `server/src/test/java/io/rwa/server/publicdata/PublicDataRepositoryJpaIntegrationTest.java`
- 2026-02-22: Updated faucet API contract to support human-readable amount input.
  - Scope: `/admin/faucet/musd` request contract and test/tooling alignment.
  - Added `amountHuman` input (decimal) with server-side conversion to raw 18-decimal amount.
  - Kept backward compatibility for existing `amount` raw input.
  - Added validation for mutually exclusive fields and scale/positivity constraints.
  - Impacted files/components:
    - `server/src/main/java/io/rwa/server/trade/FaucetRequest.java`
    - `server/src/main/java/io/rwa/server/trade/AdminAssetService.java`
    - `server/src/test/java/io/rwa/server/trade/AdminAssetServiceTest.java`
    - `server/scripts/smoke_e2e.sh`
    - `server/scripts/postman/rwa-server-e2e.postman_collection.json`
    - `server/scripts/postman/rwa-server-local.postman_environment.json`
    - `server/scripts/README.md`
- 2026-02-22: Strengthened tx outbox observability under failure.
  - Scope: tx orchestrator transaction boundary.
  - `submitContractTx()` changed to independent transaction boundary (`REQUIRES_NEW`) and preserves FAILED outbox traces.
  - Impacted files/components:
    - `server/src/main/java/io/rwa/server/tx/TxOrchestratorService.java`
- 2026-02-22: Aligned public asset bucket/unit ID conventions with AGENTS global rules.
  - Updated classKey mapping in public import:
    - `kaptMparea60 -> MPAREA_LE_60`
    - `kaptMparea85 -> MPAREA_60_85`
    - `kaptMparea135 -> MPAREA_85_135`
    - `kaptMparea136 -> MPAREA_GE_136`
  - Updated unitId format to `{kaptCode}|{classKey}|{pad5(unitNo)}`.
  - Added DB migration for existing rows:
    - `V12__asset_key_alignment_agents.sql` (legacy classKey rename + unitId format rewrite)
  - Kept `class_id` stable by using legacy hash-basis compatibility mapping in code.
  - Impacted files/components:
    - `server/src/main/java/io/rwa/server/publicdata/PublicDataService.java`
    - `server/src/test/java/io/rwa/server/publicdata/PublicDataRepositoryJpaIntegrationTest.java`
    - `server/src/main/resources/db/migration/V12__asset_key_alignment_agents.sql`
