# RWA x CryptoOrder AKS 실사용 거래 시나리오 (Postman)

## 파일
- 컬렉션: `server/scripts/postman/rwa-cryptoorder-aks-user-scenario.postman_collection.json`
- 환경: `server/scripts/postman/rwa-cryptoorder-aks-user-scenario.postman_environment.json`

## 포함된 시나리오
1. RWA / CryptoOrder Health + JWKS 확인
2. 판매자/구매자 회원가입 + 로그인 + refresh
3. `/me` 폴링으로 양쪽 지갑 프로비저닝 완료 확인
4. 관리자 컴플라이언스 승인 (seller/buyer)
5. 토큰화 자산 선택(`/tokens`) + 구매자 mUSD 지급 + 판매자 share 분배
6. 판매자 매물 등록(`/trade/list`) 및 outbox MINED 확인
7. 구매자 매수 체결(`/trade/buy`) 및 outbox MINED 확인
8. trades/holdings/read-model 반영 확인
9. 최종 시스템 상태(`/system/data-freshness`) 확인

## 환경 변수
- `cryptoorder_base_url`: 기본 `http://20.249.140.59`
- `rwa_base_url`: 기본 `http://20.249.146.251`
- `provider`: 기본 `MEMBER`
- `admin_token` (secret): 필수
- `wallet_poll_max_attempts`: 기본 `40`
- `tx_poll_max_attempts`: 기본 `240`
- `listing_poll_max_attempts`: 기본 `240`
- `trades_poll_max_attempts`: 기본 `240`
- `trade_amount_raw`: 기본 `1000000000000`
- `trade_credit_raw`: 기본 `100000000`

## 실행 방법
1. Postman에서 컬렉션/환경 파일 Import
2. 환경에서 `admin_token` 입력
3. Collection Runner에서 컬렉션 전체 실행
4. Runner 옵션에서 `Delay`를 `1000ms` 이상으로 설정 권장

## 동작 메모
- `/me`는 `WALLET_NOT_PROVISIONED(404)` 시 자동 재시도합니다.
- listing/trades/holdings는 read-model 지연을 고려해 자동 폴링합니다.
- `/tokens` 결과가 비어 있으면 즉시 실패(`토큰화된 자산 없음`) 처리합니다.
- `trade/list`, `trade/buy`에서 approval outbox가 생성되지 않으면 optional poll 단계는 자동 skip 처리됩니다.

## 자동 순차 실행 (결과만 확인)
아래 스크립트는 요청 순서대로 자동 실행하고, 응답이 끝나야 다음 요청으로 넘어갑니다.

```bash
ADMIN_TOKEN='<YOUR_ADMIN_TOKEN>' \
/Users/kanghyoseung/Desktop/project/rwa/server/scripts/postman/run-rwa-cryptoorder-aks-user-scenario-newman.sh
```

옵션:
- `--delay-ms 1000`: 요청 간 딜레이(ms), 기본 1000
- `--rwa-base-url http://...`: 환경의 RWA URL 임시 override
- `--cryptoorder-base-url http://...`: 환경의 CryptoOrder URL 임시 override

실행 후 JSON 리포트가 아래에 저장됩니다.
- `server/scripts/postman/results/rwa-cryptoorder-aks-user-scenario-<timestamp>.json`
