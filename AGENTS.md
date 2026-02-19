# AGENTS
This repository is organized into shared assets, smart-contract tooling, backend APIs, an event ingester, Flink processing, and local infrastructure.

- shared: cross-module constants, ABIs, deployment addresses, DB schema, and event signature lists.
- contracts: Hardhat project for Solidity contracts and tests.
- server: Spring Boot API server built with Gradle.
- ingester: Kafka producer ingester built with Gradle.
- flink: Flink consumer/processor job built with Gradle.
- infra: local docker-compose for Postgres and Kafka.

[GLOBAL SPEC - MUST FOLLOW]

1) Network
- Target: GIWA Sepolia
- chainId: 91342
- RPC: https://sepolia-rpc.giwa.io
- Flashblocks RPC (optional): https://sepolia-rpc-flashblocks.giwa.io
- Explorer(Blockscout): https://sepolia-explorer.giwa.io
- Note: public RPC endpoints are rate-limited, not for production.

2) Asset modeling (public data constraints)
- Complex = kaptCode
- Class = kaptCode + classKey (MPAREA_*), where classKey is one of:
  - MPAREA_LE_60 (kaptMparea60)
  - MPAREA_60_85 (kaptMparea85)
  - MPAREA_85_135 (kaptMparea135)
  - MPAREA_GE_136 (kaptMparea136)
  - MPAREA_UNKNOWN (delta bucket when hoCnt mismatch)  <-- ALWAYS ENABLED (Option A)
- Unit = virtual apartment unit inside a Class
  - unitNo = 1..unitCount
  - unitId = `${kaptCode}|${classKey}|${pad5(unitNo)}`
    - pad5 example: 1 -> "00001", 10 -> "00010", 10000 -> "10000"

3) Tokenization (ERC-1155)
- One ERC-1155 contract for all share tokens: PropertyShare1155
- Each Unit corresponds to a unique tokenId (NOT fungible within same tokenId):
  - tokenId assigned by range allocation per Class:
    - baseTokenId reserved for class
    - tokenId = baseTokenId + (unitNo - 1)
- Share unit scale:
  - SHARE_SCALE = 1e18
  - Each Unit tokenId total supply = 1e18 (so 0.1 unit share = 1e17)

4) Compliance (MVP)
- Allowlist enforced on-chain:
  - Market, Treasury, Issuer/Admin, all user wallets must be allowlisted
- Share1155 transfer restriction rule:
  - For normal transfers: operator, from, to must be allowlisted
  - Mint/Burn exceptions (from==0x0 or to==0x0)

5) Atomic settlement (FINANCIAL-GRADE DvP)
- Settlement MUST be atomic at on-chain level:
  - Payment token (ERC-20 mUSD) transfer + Share token (ERC-1155) transfer executed inside ONE Market.buy() tx.
  - If either leg fails => revert => both legs rolled back.
- Service-level truth:
  - Trade is considered FINAL only when Bought event is confirmed (>= confirmations).
  - Direct ERC-20 transfers outside Market are NOT treated as a trade.

6) Indexing pipeline
- Ingester polls logs from chain and produces to Kafka topic: chain.logs.raw
- Flink consumes chain.logs.raw, decodes events, dedups, and updates Postgres read models:
  - listings, trades, balances (+ processed_events)
- Confirmations rule:
  - Ingester only emits logs up to toBlock = latest - CONFIRMATIONS
  - CONFIRMATIONS default: 20 (configurable)
