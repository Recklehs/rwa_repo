# RWA x CryptoOrder AKS 사용자 시나리오 (Postman)

## 파일
- 컬렉션: `server/scripts/postman/rwa-cryptoorder-aks-user-scenario.postman_collection.json`
- 환경: `server/scripts/postman/rwa-cryptoorder-aks-user-scenario.postman_environment.json`

## 시나리오 범위
1. RWA / CryptoOrder Health + JWKS 확인
2. CryptoOrder 회원가입/로그인/토큰 갱신
3. RWA `/me` 폴링으로 지갑 프로비저닝 완료 확인
4. RWA 지갑/보유자산/주문/체결/마켓 조회

## 실행 방법
1. Postman에서 컬렉션/환경 파일을 Import
2. 환경을 `RWA-CryptoOrder AKS User Scenario (20.249.x.x)`로 선택
3. Collection Runner에서 컬렉션 전체 실행

## 참고
- `/me`에서 `WALLET_NOT_PROVISIONED`(404)가 나오면 컬렉션이 자동으로 재시도합니다.
- 기본 재시도 횟수는 `poll_max_attempts=30`이며 필요하면 환경 변수에서 조정할 수 있습니다.
