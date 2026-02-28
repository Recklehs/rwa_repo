# CryptoOrder Server Spec

## 1. Scope
- 본 문서는 현재 `CryptoOrder` 코드 기준 구현 스펙을 설명한다.
- 컨트롤러 API는 크게 두 계열이다.
  - 토큰 발급 중심: `/auth/**` + `/.well-known/jwks.json`
  - 레거시 계정/은행 API: `/api/auth/**`, `/api/account/**`, `/api/bank/**`
- `Account` 도메인(계정/원화/포인트)은 서비스/엔티티만 있는 상태가 아니라 컨트롤러도 존재한다.

## 2. Runtime / Stack
- Java 21
- Spring Boot `3.5.10`
- Spring Web, Data JPA, Validation, Security, Actuator
- Spring Kafka (wallet-create outbox 발행)
- Scheduler (`@EnableScheduling`) 기반 outbox dispatcher
- JWT: Nimbus JOSE + JWT (`RS256`/`HS256`)
- DB: PostgreSQL (runtime), H2 (test)
- Metric: Micrometer Prometheus registry (`/actuator/prometheus`)

## 3. HTTP API Surface

### 3.1 Token Auth API (`/auth`)

#### `POST /auth/signup`
- Headers
  - `Idempotency-Key` (필수, 공백 불가, 최대 200자)
- Body
  - `name` (required)
  - `phone` (required, `010-1234-5678`)
  - `birthDate` (required, ISO date)
  - `loginId` (required)
  - `password` (required, 최소 8자)
  - `provider` (optional, 기본 `MEMBER`)
  - `externalUserId` (optional)
- 처리
  - 사용자/로그인 계정/네이버포인트 지갑 생성
  - 가입 재시도 시 같은 `loginId`라도 비밀번호/provider/externalUserId가 기존과 같으면 기존 계정을 재사용
  - 지갑 생성 연동
    - `integration.wallet-create.enabled=true`: outbox 이벤트 enqueue
    - 그 외: `custody.provision.enabled=true`일 때 동기 HTTP 프로비저닝 호출
  - access/refresh 토큰 발급
- Response `200 OK`
  - `tokenType`, `accessToken`, `accessTokenExpiresIn`, `refreshToken`, `refreshTokenExpiresIn`, `userId`

#### `POST /auth/login`
- Headers
  - `Idempotency-Key` (필수)
- Body
  - `loginId` (required)
  - `password` (required)
- 처리
  - ID/PW 검증
  - access/refresh 토큰 발급
- Response `200 OK`

#### `POST /auth/refresh`
- Headers
  - `Idempotency-Key` (필수)
- Body
  - `refreshToken` (required)
- 처리
  - refresh token 회전(기존 토큰 revoke)
  - 신규 access/refresh 토큰 발급
- Response `200 OK`

### 3.2 JWKS API
- `GET /.well-known/jwks.json`
- `auth.mode=JWKS`: 공개키(`kid=auth.jwt-key-id`)를 `keys[]`로 반환
- `auth.mode=HMAC`: `{ "keys": [] }` 반환

### 3.3 Legacy Auth API (`/api/auth`)
- 이 경로는 별도 레거시 컨트롤러로 동작하며 `Idempotency-Key`를 사용하지 않는다.

#### `POST /api/auth/signup`
- Body
  - `name`, `phoneNumber`, `birthDate`, `loginId`, `password`
- Response `200 OK`
  - `userHexId`, `userName`, `phoneNumber`, `createdAt`, `isActive`

#### `POST /api/auth/login`
- Body
  - `loginId`, `password`
- Response `200 OK`
  - `userHexId`, `userName`, `phoneNumber`, `createdAt`, `isActive`

### 3.4 Account/Bank API

#### `GET /api/account/{accountId}/balance`
- Response `200 OK`
  - `accountNumber`, `balance`, `isActive`

#### `POST /api/bank/deposit`
#### `POST /api/bank/withdraw`
- Body
  - `accountId` (UUID)
  - `amount` (positive long)
  - `counterparty` (required string)
- Response `200 OK`
  - `transactionId`, `transactionType`, `status`, `amount`, `balanceAfterTransaction`, `transactionTime`

## 4. JWT / JWKS Spec

### 4.1 Access Token Claims
- `sub`: user UUID
- `iss`: `auth.jwt-issuer`
- `aud`: `auth.jwt-audience`
- `iat`, `exp`, `jti`
- `roles`: `["USER"]`
- `provider` (값이 있을 때만)
- `externalUserId` (값이 있을 때만)

### 4.2 Signing Algorithm
- `auth.mode=JWKS` -> `RS256`
- `auth.mode=HMAC` -> `HS256`

### 4.3 키 로딩 정책
- JWKS 모드
  - 다음 쌍 중 하나로 RSA 키 제공
    - base64: `auth.jwt-private-key-base64` + `auth.jwt-public-key-base64`
    - 파일경로: `auth.jwt-private-key-path` + `auth.jwt-public-key-path`
  - base64와 path 동시 지정 금지
  - 키 미지정 시 `auth.allow-ephemeral-keys=true`일 때만 임시 키 허용
- HMAC 모드
  - `auth.hmac-secret-base64` 필수
  - 디코드 기준 최소 32바이트

## 5. Idempotency Spec (`/auth/*`에 적용)
- 적용 대상
  - `POST /auth/signup`
  - `POST /auth/login`
  - `POST /auth/refresh`
- 키 구성
  - 내부 request key: `{operation}:{Idempotency-Key}`
  - 예: `POST:/auth/signup:signup-member-a`
- payload 무결성
  - 요청 body SHA-256 해시 저장
  - 동일 키 + 다른 payload면 `409 Conflict`
- 상태
  - `IN_PROGRESS`, `COMPLETED`
- 처리 규칙
  - 동일 키가 `IN_PROGRESS`이고 lock TTL 이내면 `202 Accepted`
  - lock TTL 경과한 stale `IN_PROGRESS`면 락 연장 후 재처리
  - `COMPLETED`면 저장된 응답 replay
- 응답 저장
  - 상태코드 + body 저장
  - body는 AES-GCM으로 암호화 저장 (`idempotency.response-encryption-key-base64`)
  - 키 미설정 시 `idempotency.allow-ephemeral-encryption-key=true`일 때만 임시 키 허용

## 6. Signup Wallet Provision Flow
1. `signup` 수신 + idempotency 처리 시작
2. 사용자/계정 생성(또는 동일 loginId 재시도면 계정 재사용)
3. 지갑 생성 연동 분기
   - `integration.wallet-create.enabled=true`: outbox 저장 후 dispatcher가 Kafka 발행
   - `integration.wallet-create.enabled=false`: 커스터디 HTTP 호출(`custody.provision.enabled=true`일 때)
4. access/refresh 토큰 발급

- 재시도 특성
  - 동일 `loginId` 재시도 시 비밀번호/provider/externalUserId가 기존과 다르면 중복 오류 처리
  - outbox 모드에서는 `wallet_create_outbox_events` 테이블 상태(`PENDING/FAILED/SENT/DEAD`)로 재시도 관리

## 7. Security Policy
- Permit-all 경로
  - `/auth/**`
  - `/api/auth/**`
  - `/.well-known/jwks.json`
  - `/h2-console/**`
  - actuator endpoint: `health`, `info`, `prometheus`
- 그 외 경로는 인증 필요
- `httpBasic`, `formLogin`, `logout` 비활성화
- Password encoder: `DelegatingPasswordEncoder` (기본 bcrypt)

## 8. Error Response / Status Mapping

### 8.1 기본 오류 포맷 (`common.api.GlobalExceptionHandler`)
- 포맷: `timestamp`, `status`, `error`, `message`, `path`
- 주요 매핑
  - `UnauthorizedException` -> `401`
  - `UpstreamServiceException` -> `503`
  - `IdempotencyConflictException` -> `409`
  - `IdempotencyInProgressException` -> `202`
  - `IllegalArgumentException` -> `400`
  - `IllegalStateException` -> `409`
  - `MethodArgumentNotValidException` -> `400`
  - `MissingRequestHeaderException` -> `400`
  - `HttpMessageNotReadableException` -> `400`
  - `AuthenticationException` -> `401`
  - `AccessDeniedException` -> `403`
  - 기타 예외 -> `500`

### 8.2 Account 컨트롤러 전용 오류 포맷 (`config.AccountGlobalExceptionHandler`)
- 대상 패키지: `com.example.cryptoorder.Account.controller`
- 포맷: `errorCode`, `message`, `timestamp`

## 9. Domain / Persistence (요약)
- 주요 엔티티
  - `User`, `Account`
  - `NaverPoint`, `NaverPointHistory`
  - `KRWAccount`, `KRWTransaction`, `KRWAccountHistory`
  - `RefreshToken` (DB에는 `tokenHash`만 저장)
  - `IdempotencyRecord`
  - `WalletCreateOutboxEvent`
- 서비스 레이어에 입금/출금/회원탈퇴 로직 및 비관적 락 사용

## 10. Config Keys (주요)

### 10.1 Auth
- `AUTH_MODE` (`JWKS`/`HMAC`)
- `AUTH_JWT_ISSUER`
- `AUTH_JWT_AUDIENCE`
- `AUTH_ACCESS_TOKEN_TTL_SECONDS`
- `AUTH_REFRESH_TOKEN_TTL_SECONDS`
- `AUTH_JWT_KEY_ID`
- `AUTH_JWT_PRIVATE_KEY_BASE64` / `AUTH_JWT_PUBLIC_KEY_BASE64`
- `AUTH_JWT_PRIVATE_KEY_PATH` / `AUTH_JWT_PUBLIC_KEY_PATH`
- `AUTH_HMAC_SECRET_BASE64`
- `AUTH_ALLOW_EPHEMERAL_KEYS`

### 10.2 Custody Provision (동기 HTTP)
- `CUSTODY_PROVISION_ENABLED`
- `CUSTODY_BASE_URL`
- `CUSTODY_PROVISION_PATH`
- `SERVICE_TOKEN`
- `SERVICE_TOKEN_HEADER`
- `CUSTODY_CONNECT_TIMEOUT_MILLIS`
- `CUSTODY_READ_TIMEOUT_MILLIS`

### 10.3 Wallet Create Outbox / Kafka
- `WALLET_CREATE_INTEGRATION_ENABLED`
- `WALLET_CREATE_TOPIC`
- `WALLET_CREATE_BATCH_SIZE`
- `WALLET_CREATE_DISPATCH_INTERVAL_MS`
- `WALLET_CREATE_RETRY_BACKOFF_MS`
- `WALLET_CREATE_MAX_ATTEMPTS`
- `KAFKA_BOOTSTRAP_SERVERS`
- `KAFKA_SECURITY_PROTOCOL`
- `KAFKA_SASL_MECHANISM`
- `KAFKA_SASL_JAAS_CONFIG`

### 10.4 Idempotency
- `IDEMPOTENCY_IN_PROGRESS_TTL_SECONDS`
- `IDEMPOTENCY_RESPONSE_ENCRYPTION_KEY_BASE64`
- `IDEMPOTENCY_ALLOW_EPHEMERAL_ENCRYPTION_KEY`

## 11. Verified by Tests
- `/auth/signup` 토큰/claim 발급 검증
- `/auth/login` + `/auth/refresh` refresh rotation 검증
- JWKS endpoint 응답 검증
- HMAC 모드 HS256 발급 검증
- idempotency replay/conflict/in-progress/stale 복구 검증
- 공통 예외 응답 포맷 검증
- 레거시 `/api/auth` 컨트롤러 검증
- 계정/입출금 서비스 단위/통합/동시성 테스트 검증
