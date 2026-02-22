# CODEX SPEC 4 - UPDATED v4.1.1 (Aligned with SPEC2 MERGED v4.1, updated 2026-02-22)

Title: Flink Indexer Job (Kafka `chain.logs.raw` -> Postgres read models)  
Baseline: 2026-02-15 post-live deploy  
Alignment: `server/codex_spec.md` (SPEC2) is the source of truth for server behavior and DB read-model usage.

## 0) Baseline alignment (MUST)

### A) Compliance model
- On-chain allowlist/KYC model is removed.
- Flink MUST NOT reference allowlist/KYC contracts or events.
- Compliance is enforced off-chain by server before tx submission.
- Public chain may still include external events; optional filtering modes are defined in Section 7.

### B) Deployed contract set (EXACTLY 5)
1. MockUSD
2. PropertyRegistry
3. PropertyShare1155
4. PropertyTokenizer
5. FixedPriceMarketDvP

### C) Runtime source of truth (MUST)
- Addresses: `shared/deployments/giwa-sepolia.json`
- ABIs: `shared/abi/*.json` (no legacy allowlist ABI)
- Constants: `shared/config/constants.json`
  - `SHARE_SCALE = 1e18`
  - `CONFIRMATIONS = 12` (applied by Ingester; Flink does not re-apply)
- No hardcoded addresses/topic hashes/constants.

### D) Network / input
- `chainId = 91342` (GIWA Sepolia)
- Kafka input topic: `chain.logs.raw`

## 1) Responsibilities

Flink job MUST:
- Consume raw chain log envelopes from Kafka (at-least-once input semantics).
- Identify target events by runtime-loaded `(contractAddress + topic0)` mapping.
- Decode events:
  - Market: `Listed`, `Bought`, `Cancelled`
  - ERC1155: `TransferSingle`, `TransferBatch`
- Deduplicate with `processed_events` and update read models idempotently.
- Tolerate duplicates and limited out-of-order delivery.
- Keep server query read models up to date:
  - `/market/listings`
  - `/users/{userId}/holdings`
  - `/tokens/{tokenId}/holders`

Flink job MUST NOT:
- Submit on-chain transactions
- Enforce compliance gate logic
- Re-apply confirmations logic already handled by Ingester

## 2) DB schema prerequisites (MUST)

Read-model constraints required for idempotent application:

### A) `processed_events`
- `UNIQUE(event_key)` required.
- Recommended key format: `"{chainId}:{txHashLower}:{logIndex}"`

### B) `balances`
- Must support multiple token IDs per owner.
- `PRIMARY KEY(owner, token_id)` required.
- Recommended indexes:
  - `INDEX(token_id)`
  - `INDEX(owner)`

### C) `trades`
- `UNIQUE(tx_hash)` required.

### D) `listings`
- `listings.id` stores on-chain `listingId` via explicit insert/upsert.
- `property_id` stores decimal string of `tokenId` (default policy).
- Sequence should be aligned to current `max(id)` when explicit ids are used.

### Migration status (implemented)
- Added Flyway migration: `server/src/main/resources/db/migration/V11__read_model_constraints.sql`
  - dedup cleanup + unique constraints + balances composite PK + indexes + listing sequence alignment
- Shared schema aligned: `shared/db/ddl.sql`

## 3) Atomic DB apply per event (CRITICAL)

For each decoded event:
1. `INSERT INTO processed_events ... ON CONFLICT(event_key) DO NOTHING`
2. If inserted rows = 0, stop (already processed)
3. Apply read-model updates (`listings`, `trades`, `balances`)
4. Commit

If any step fails, rollback entire event transaction.

Implementation:
- `flink/src/main/java/io/rwa/flink/sink/PostgresReadModelSink.java`
- Single DB transaction per event is preserved.

## 4) Input message schema (from Ingester)

Expected fields in Kafka JSON payload:
- `chainId` (int)
- `blockNumber` (long)
- `blockTimestamp` (ISO-8601 string or epoch)
- `txHash`
- `logIndex`
- `contractAddress`
- `topics[]`
- `data`
- `removed` (optional)
- `ingestedAt`

Rules:
- Ignore events where `chainId != 91342`.
- Ignore removed logs (`removed=true`).

## 5) Event identification and decoding (no hardcoding)

Startup behavior:
- Load deployment + constants + ABI from `shared/*`.
- Compute topic0 from ABI canonical event signatures.
- Build lookup map by `(contractAddress, topic0)`.

Enabled decoded events in current implementation:
- `FixedPriceMarketDvP`: `Listed`, `Bought`, `Cancelled`
- `PropertyShare1155`: `TransferSingle`, `TransferBatch`

Decoder implementation:
- `flink/src/main/java/io/rwa/flink/decoder/ChainLogEventDecoder.java`
- Uses web3j ABI decoder for non-indexed data.

## 6) Read-model update rules

All updates below run only when dedup insert succeeds.

### 6-A) `Market.Listed`
- Upsert listing row:
  - `id = listingId`
  - `property_id = tokenId(decimal string)`
  - `listing_status = ACTIVE`
  - `price = unitPrice`
  - timestamps from block time
- If fill hint indicates full amount already purchased, mark `FILLED`.

### 6-B) `Market.Bought`
- Insert trade:
  - `listing_id`, `buyer`, `seller`, `tx_hash`, `traded_at`, `amount`
  - `ON CONFLICT(tx_hash) DO NOTHING`
- Update listing `updated_at`
- If missing listing, upsert ACTIVE stub (`id`, `property_id`, `price`, timestamps)
- Fill handling:
  - Flink keyed state tracks listed/purchased amounts best-effort
  - when `purchased >= listed`, set listing status to `FILLED`
- Cost handling:
  - event `cost` used if present
  - fallback formula available: `amount * unitPrice / SHARE_SCALE`

### 6-C) `Market.Cancelled`
- Set `listing_status = CANCELLED`, update timestamp
- If missing listing, insert CANCELLED stub row

### 6-D) `ERC1155.TransferSingle`
- If `from != 0x0`: `balances[from, token_id] -= value`
- If `to   != 0x0`: `balances[to, token_id] += value`
- Upsert delta with `ON CONFLICT(owner, token_id)`

### 6-E) `ERC1155.TransferBatch`
- Apply same delta logic for each `(id, value)` pair

Negative amount policy:
- If resulting balance is negative, log anomaly and continue.
- No silent clamping.

## 7) Optional filtering modes

Because contracts are public, optional filter modes exist:
- `FILTER_MODE=ALL` (default): index all events
- `FILTER_MODE=KNOWN_WALLETS`: only if participant address exists in `wallets`
- `FILTER_MODE=APPROVED_WALLETS`: only if participant maps to `users.compliance_status=APPROVED`

Implementation note:
- Filtering is applied after dedup insert within same event transaction.
- Filtering checks are DB-backed.

## 8) Checkpointing and delivery semantics

- Flink checkpoints enabled.
- Kafka source offsets are checkpoint-managed.
- DB sink uses event-transaction semantics and dedup for retry safety.
- If DB fails, event is retried; dedup prevents double-apply.

## 9) Runtime env/config

Main env keys (see `flink/.env.example`):
- Shared/config:
  - `SHARED_DIR_PATH=../shared`
  - `GIWA_CHAIN_ID=91342`
- Kafka:
  - `KAFKA_BOOTSTRAP_SERVERS`
  - `KAFKA_TOPIC=chain.logs.raw`
  - `KAFKA_GROUP_ID`
  - `KAFKA_SECURITY_PROTOCOL`, `KAFKA_SASL_MECHANISM`, `KAFKA_SASL_JAAS_CONFIG`, `KAFKA_CLIENT_DNS_LOOKUP`
- DB:
  - `DB_URL`, `DB_USER`, `DB_PASSWORD`
- Flink runtime:
  - `FLINK_CHECKPOINT_INTERVAL_MS`
  - `FLINK_PARALLELISM`
  - `SINK_PARALLELISM`
  - `FILTER_MODE`

## 10) Implementation mapping

### Entry point and pipeline
- `flink/src/main/java/io/rwa/flink/FlinkIndexerApplication.java`
  - Kafka source
  - decode
  - market fill-hint state process
  - Postgres sink

### Shared resource loading
- `flink/src/main/java/io/rwa/flink/config/SharedRuntimeResourcesLoader.java`
- `flink/src/main/java/io/rwa/flink/config/SharedRuntimeResources.java`

### Decoder
- `flink/src/main/java/io/rwa/flink/decoder/DecodeRawLogFunction.java`
- `flink/src/main/java/io/rwa/flink/decoder/ChainLogEventDecoder.java`

### Fill hint process
- `flink/src/main/java/io/rwa/flink/process/ListingFillHintProcessFunction.java`

### Sink
- `flink/src/main/java/io/rwa/flink/sink/PostgresReadModelSink.java`

### Build/runtime
- `flink/build.gradle`
- `flink/.env.example`

## 11) Acceptance criteria checklist

- No allowlist/KYC contract/event references in Flink code.
- Runtime loads addresses/ABIs/constants from `shared/*`.
- Chain ID validation (`91342`) applied in decoder.
- Dedup via `processed_events(event_key)` unique semantics.
- `balances` composite key semantics assumed and enforced by migration.
- `trades(tx_hash)` uniqueness used for idempotency.
- `listings.id` uses on-chain listingId explicit upsert.
- Duplicate Kafka messages do not double-apply business mutations.
- Out-of-order tolerance via listing stub upsert and best-effort fill state.

## 12) Operational note: sink connection model

Current sink model:
- Reuses one JDBC connection per Flink sink subtask.
- Reuses prepared statements for fixed SQL operations.
- Keeps per-event transaction boundary (commit/rollback per event).
- On recoverable connection errors (`SQLSTATE 08*` etc), reconnects and retries once.

This preserves correctness while reducing per-event connection overhead.

## 13) Tests

- `cd flink && ./gradlew test`
- Key tests:
  - `SharedRuntimeResourcesLoaderTest`
  - `ChainLogEventDecoderTest`

## 14) Update policy

- This file is the Flink module source-of-truth spec for this implementation baseline.
- Any change to Flink behavior (decode scope, dedup/transaction semantics, schema assumptions, env contract) must update this file in the same change.

## Change log

- 2026-02-22: Initialized `flink/codex_spec.md` for SPEC4 v4.1.1 baseline.
- 2026-02-22: Implemented Flink indexer pipeline for market/ERC1155 read-model indexing.
- 2026-02-22: Added DB constraint migration dependency (`V11`) and shared schema alignment.
- 2026-02-22: Refactored sink to connection/statement reuse with reconnect retry while preserving event-level atomic transactions.
