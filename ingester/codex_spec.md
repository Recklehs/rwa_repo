# CODEX SPEC 3 - UPDATED v4.1 (Aligned with SPEC2 server baseline)

Title: On-chain Log Ingester (GIWA Sepolia) -> Kafka (`chain.logs.raw`)  
Baseline: 2026-02-15 post-live deploy  
Compatibility: `SPEC2 (server/codex_spec.md) MUST`

## 0) Baseline alignment (MUST)

### A) Compliance model
- On-chain allowlist enforcement is removed.
- Ingester MUST NOT reference allowlist/KYC contracts or events.

### B) Deployed contracts set (EXACTLY 5)
1. MockUSD
2. PropertyRegistry
3. PropertyShare1155
4. PropertyTokenizer
5. FixedPriceMarketDvP

### C) Runtime source of truth (MUST)
- Contract addresses: `shared/deployments/giwa-sepolia.json`
- Event catalog: `shared/events/signatures.json`
- Constants: `shared/config/constants.json`
  - `SHARE_SCALE = 1e18`
  - `CONFIRMATIONS = 12`
- Do NOT hardcode addresses or event topics.

### D) Network config
- `chainId=91342`
- `RPC=https://sepolia-rpc.giwa.io`

## 1) Responsibilities

- Poll GIWA Sepolia logs for deployed contracts only.
- Apply confirmations: emit logs only up to `toBlock = latest - CONFIRMATIONS`.
- Produce raw log envelopes into Kafka topic `chain.logs.raw`.
- Maintain durable checkpoint: `lastProcessedBlock`.
- Provide at-least-once delivery (downstream Flink handles dedup).

Ingester does NOT:
- Enforce compliance
- Update DB read models
- Decode business meaning beyond event identification

## 2) Runtime config / env

- `SHARED_DIR_PATH`
  - local host default: `../shared`
  - AKS baseline: `/shared` (container image includes `/shared`)
- `GIWA_RPC_URL=https://sepolia-rpc.giwa.io`
- `KAFKA_BOOTSTRAP_SERVERS=...`
- `KAFKA_TOPIC=chain.logs.raw`
- `KAFKA_DLQ_TOPIC=chain.logs.dlq`
- `KAFKA_SECURITY_PROTOCOL` (optional; ex: `SASL_SSL` for Azure Event Hubs Kafka)
- `KAFKA_SASL_MECHANISM` (optional; ex: `PLAIN`)
- `KAFKA_SASL_JAAS_CONFIG` (optional; Event Hubs connection string auth)
- `KAFKA_CLIENT_DNS_LOOKUP` (AKS baseline: `use_all_dns_ips`)
- `KAFKA_ENABLE_IDEMPOTENCE` (AKS/Event Hubs baseline: `false`)
- `KAFKA_REQUEST_TIMEOUT_MS=30000`
- `KAFKA_DELIVERY_TIMEOUT_MS=120000`
- `KAFKA_LINGER_MS=5`
- `KAFKA_COMPRESSION_TYPE` (optional; ex: `gzip`)
- `POLL_INTERVAL_MS=5000`
- `MAX_BLOCK_RANGE=2000`
- `STATE_STORE=postgres|file` (AKS baseline: `postgres`)
  - postgres: table `ingester_state(key text pk, value text)`
  - file: local file path (ex: `./state/lastProcessedBlock.txt`)

Important:
- `CONFIRMATIONS` MUST be loaded from `shared/config/constants.json` (default `12`).

AKS operational baseline:
- `SHARED_DIR_PATH=/shared`
- `STATE_STORE=postgres`
- `KAFKA_CLIENT_DNS_LOOKUP=use_all_dns_ips`
- `KAFKA_ENABLE_IDEMPOTENCE=false` (Event Hubs compatibility)

## 3) What to ingest (event subscriptions)

MUST ingest:
- `shared/events/signatures.json` entries with `enabled=true`
- Address/topic filters are derived from enabled events at runtime

MUST NOT ingest:
- Legacy allowlist events

## 4) Topic filter and interpretation rule (MUST: no hardcoding)

- Load `shared/events/signatures.json` at runtime.
- Use enabled events to build per-address `topic0` filters.
- Validate each enabled event:
  - Contract name is one of the deployed 5 contracts.
  - Event address equals deployment address for that contract.
  - `topic0 == keccak256(signature)` for self-consistency.
- Interpret each ingested log by `(address, topic0)` lookup to attach event metadata.

## 5) Polling algorithm

Loop:
1. `latest = eth_blockNumber`
2. `confirmations = constants.CONFIRMATIONS`
3. `toBlock = latest - confirmations`
4. `fromBlock = lastProcessedBlock + 1`
5. if `toBlock < fromBlock`: sleep + continue

Range window:
- `windowEnd = min(fromBlock + MAX_BLOCK_RANGE - 1, toBlock)`
- For each filtered address, query `eth_getLogs` with enabled `topic0[]` for that address

Post-process:
- Merge all logs
- Sort by `(blockNumber ASC, logIndex ASC)`
- Produce each to Kafka

Checkpoint rule:
- Advance `lastProcessedBlock = windowEnd` only after ALL logs in window are produced successfully.

Error handling:
- `eth_getLogs` too large/too many results -> halve range and retry
- Kafka send fail -> do NOT advance checkpoint, retry send
- Unknown event policy:
  - `SKIP`: drop unknown event log
  - `FAIL`: throw and retry loop without checkpoint advance
  - `DLQ`: publish unknown event envelope to `KAFKA_DLQ_TOPIC`

## 6) Kafka message schema (raw envelope)

Topic: `chain.logs.raw`  
Recommended key: `{chainId}` (single-partition ordering guidance)

Value JSON:

```json
{
  "chainId": 91342,
  "network": "giwaSepolia",
  "blockNumber": 123456,
  "blockHash": "0x...",
  "blockTimestamp": "2026-02-19T12:34:56Z",
  "txHash": "0x...",
  "txIndex": 7,
  "logIndex": 12,
  "contractAddress": "0x...",
  "topics": ["0x...", "0x..."],
  "data": "0x...",
  "removed": false,
  "dedupKey": "91342:0x...:12",
  "eventKnown": true,
  "eventId": "market.listed",
  "eventContract": "FixedPriceMarketDvP",
  "eventSignature": "Listed(uint256,address,address,uint256,address,uint256,uint256)",
  "eventTopic0": "0x...",
  "ingestedAt": "2026-02-19T12:35:10Z"
}
```

Unknown event envelope (only when `unknownEventPolicy=DLQ`):
- `eventKnown=false`
- `eventId=unknown`
- `unknownEventPolicy=DLQ`
- `routedToDlq=true`
- `dlqReason=UNKNOWN_EVENT`
- `sourceTopic=<KAFKA_TOPIC>`
- `destinationTopic=<KAFKA_DLQ_TOPIC>`

Ordering guidance:
- Prefer topic partitions = 1, or key by `{chainId}`.
- If multi-partition, downstream Flink must handle out-of-order.

## 7) Acceptance criteria

- No legacy allowlist contract/topic references.
- Reads addresses/event-catalog/constants from `shared/*` at runtime.
- Emits enabled `signatures.json` logs only up to `(latest - 12)`.
- Produces messages ordered by `(blockNumber, logIndex)`.
- Unknown events are routed per policy (`SKIP`/`FAIL`/`DLQ`) and `DLQ` goes to `KAFKA_DLQ_TOPIC`.
- Restart resumes from `lastProcessedBlock` without gaps.
- Duplicates are acceptable (downstream dedup).

## 8) AKS image/deploy policy (MUST)

- Use repository Dockerfile: `ingester/Dockerfile`
- Build/push with `docker buildx` and fixed target platform:
  - `--platform linux/amd64`
  - `-f ingester/Dockerfile`
  - `--push`
- Runtime image must be deterministic:
  - build stage: `./gradlew clean installDist`
  - runtime entrypoint: `/app/bin/ingester`
  - include `/shared` in image for `SHARED_DIR_PATH=/shared` compatibility
- Deploy assets live under `ingester/k8s/aks`:
  - `create-env-secret.sh`, `deploy.sh`, `deployment.yaml`, `README.md`
- `.env` split policy:
  - ConfigMap: non-sensitive keys
  - Secret: `KAFKA_SASL_JAAS_CONFIG,DB_URL,DB_USER,DB_PASSWORD`
- Workload policy:
  - `Deployment` only (no Service)
  - single replica (`replicas: 1`)
  - rollout strategy avoids overlap (`Recreate`)
- Image tag policy:
  - immutable tags only (`YYYYMMDD-HHMM-gitsha`)
  - `latest` tag forbidden

## Implementation mapping (2026-02-19)

- Runtime loader implemented:
  - `ingester/src/main/java/io/rwa/ingester/config/SharedResourcesLoader.java`
  - Loads deployment/signatures/constants at startup.
  - Enforces deployed contracts set is exactly the required 5.
  - Builds runtime filters from `signatures.json` enabled events.
  - Resolves `(address, topic0)` -> event metadata for envelope.
- Polling + confirmations + window/range reduction:
  - `ingester/src/main/java/io/rwa/ingester/service/LogIngestionService.java`
- Checkpoint stores:
  - file: `FileStateStore`
  - postgres: `PostgresStateStore` (`ingester_state` auto-create)
- Kafka producer and raw/dlq envelope routing:
  - `ingester/src/main/java/io/rwa/ingester/kafka/KafkaRawLogPublisher.java`
  - Topic defaults: `chain.logs.raw` and `chain.logs.dlq`
- AKS image/deploy assets:
  - `ingester/Dockerfile`
  - `ingester/k8s/aks/create-env-secret.sh`
  - `ingester/k8s/aks/deploy.sh`
  - `ingester/k8s/aks/deployment.yaml`
  - `ingester/k8s/aks/README.md`

## Update policy

- `ingester/codex_spec.md` is the ingester module's source-of-truth spec for this request.
- Any ingester behavior change (polling, checkpointing, schema, env, contract/event scope) MUST update this file in the same change.

## Change log

- 2026-02-25: Added AKS image/deploy baseline for ingester to prevent server-class rollout failures.
  - Added deterministic runtime image definition `ingester/Dockerfile` using `installDist` output (`/app/bin/ingester`) and bundled `/shared`.
  - Standardized AKS image build to `linux/amd64` via `docker buildx build --platform linux/amd64 ... --push`.
  - Added AKS deployment assets under `ingester/k8s/aks`:
    - `.env` split into `rwa-ingester-config` (ConfigMap) and `rwa-ingester-secret` (Secret).
    - Deployment runs single replica with `Recreate`, no Service.
    - Enforced immutable image tags (`latest` disallowed in deploy script).
    - Added `KAFKA_CLIENT_DNS_LOOKUP=use_all_dns_ips` and `STATE_STORE=postgres` validation in env apply script.
  - Set AKS operational defaults in env contract: `SHARED_DIR_PATH=/shared`, `STATE_STORE=postgres`.
- 2026-02-25: Added Kafka idempotence toggle for Event Hubs compatibility.
  - Added runtime env `KAFKA_ENABLE_IDEMPOTENCE` (default `false`).
  - Wired producer setting `enable.idempotence` to env-driven toggle.
  - Updated env template/AKS baseline docs to keep Event Hubs default at `false`.
- 2026-02-19: Initialized SPEC3 v4.1 baseline in ingester.
  - Added runtime shared loaders (`shared/deployments`, `shared/abi`, `shared/config/constants`) and ABI-derived topic computation.
  - Added windowed log polling with confirmations and adaptive range reduction.
  - Added at-least-once Kafka publishing and durable checkpoint stores (`file`/`postgres`).
  - Added env template and initial unit test for ABI event topic resolution.
- 2026-02-19: Updated event filtering/interpretation to `shared/events/signatures.json`.
  - Replaced fixed contract-event subscription logic with enabled-event catalog loading.
  - Added validation for contract/address/topic consistency against deployment and signature hash.
  - Added envelope metadata fields (`dedupKey`, `eventId`, `eventContract`, `eventSignature`, `eventTopic0`, `eventKnown`).
- 2026-02-19: Added Kafka security/network env support for Azure Event Hubs compatibility.
  - Added optional SASL/SSL producer settings (`KAFKA_SECURITY_PROTOCOL`, `KAFKA_SASL_MECHANISM`, `KAFKA_SASL_JAAS_CONFIG`, `KAFKA_CLIENT_DNS_LOOKUP`).
  - Added producer timeout/tuning env settings (`KAFKA_REQUEST_TIMEOUT_MS`, `KAFKA_DELIVERY_TIMEOUT_MS`, `KAFKA_LINGER_MS`, `KAFKA_COMPRESSION_TYPE`).
  - Updated `ingester/.env.example` with Event Hubs Kafka endpoint sample.
- 2026-02-22: Implemented unknown-event DLQ routing.
  - Added `KAFKA_DLQ_TOPIC` runtime config (default `chain.logs.dlq`).
  - Added topic-aware Kafka publish API and routing logic in ingestion service.
  - Added unknown-event DLQ metadata fields (`routedToDlq`, `dlqReason`, `sourceTopic`, `destinationTopic`).
- 2026-02-23: Fixed unknown-event `FAIL` handling to match loop-level retry semantics.
  - Unknown event on `FAIL` now throws out of per-log publish retry loop.
  - Polling loop retries the same window without advancing checkpoint, per spec.
