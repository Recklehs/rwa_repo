# CryptoOrder
Spring Server which handles basic function that is necessary to Crypto Exchange

## Local Runtime (Azure-free)

- `src/main/resources/application.properties` is git-ignored.
- Use `src/main/resources/application.properties.example` as the template.
- `src/main/resources/application.properties` imports `.env` automatically with:
  - `spring.config.import=optional:file:.env[.properties]`
- Server runtime target is local PostgreSQL.
- Azure/AKS path is retired and intentionally disabled (`server/k8s/aks/*` is archival only).

### Prerequisites
- Java 21
- PostgreSQL (local)
- Optional: Kafka (only if `WALLET_CREATE_INTEGRATION_ENABLED=true`)

### Quick start
1. Create local env file.
   - `cp .env.example .env`
2. Start PostgreSQL and create DB/user if needed.
   - DB: `cryptoorder`
   - User: `cryptoorder`
   - Password: `cryptoorder`
   - Optional (Docker): `docker run --name cryptoorder-postgres -e POSTGRES_DB=cryptoorder -e POSTGRES_USER=cryptoorder -e POSTGRES_PASSWORD=cryptoorder -p 5432:5432 -d postgres:16`
3. Check `.env` local defaults (already Azure-free in `.env.example`):
   - `DB_URL=jdbc:postgresql://localhost:5432/cryptoorder`
   - `CUSTODY_PROVISION_ENABLED=false`
   - `WALLET_CREATE_INTEGRATION_ENABLED=false`
   - `KAFKA_BOOTSTRAP_SERVERS=localhost:9092`
4. Run server.
   - `./gradlew bootRun`

### Key env vars
- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- optional: `DB_SCHEMA` (default `public`, example `cryptoorder_app`)
- optional: `JPA_DDL_AUTO` (default `update`)

### Security-related local defaults
- `.env.example` enables ephemeral keys by default:
  - `AUTH_ALLOW_EPHEMERAL_KEYS=true`
  - `IDEMPOTENCY_ALLOW_EPHEMERAL_ENCRYPTION_KEY=true`
- For production-like runs, set both values to `false` and provide fixed keys.

### Tests
- Tests use in-memory H2 (`src/test/resources/application-test.properties`).
- Run tests:
  - `./gradlew test`

## Observability
- Actuator endpoints are enabled for `health`, `info`, and `prometheus`.
- Prometheus scrape endpoint: `/actuator/prometheus`
- Health endpoint: `/actuator/health`
