# RWA x CryptoOrder K8s Postman 점검 가이드

## 파일
- 컬렉션: `server/scripts/postman/rwa-cryptoorder-k8s-deploy-check.postman_collection.json`
- 환경 템플릿: `server/scripts/postman/rwa-cryptoorder-k8s-deploy-check.postman_environment.example.json`

## 사전 설정
1. 환경 파일을 로컬용으로 복사합니다.
   - `cp server/scripts/postman/rwa-cryptoorder-k8s-deploy-check.postman_environment.example.json server/scripts/postman/rwa-cryptoorder-k8s-deploy-check.postman_environment.json`
2. Postman에 컬렉션/환경 파일을 임포트합니다.
3. 환경 변수 값을 현재 배포 값으로 채웁니다.
- `cryptoorder_base_url`: `http://<cryptoorder-lb-ip>`
- `rwa_base_url`: `http://<rwa-lb-ip>`
4. 관리자/내부 API 검증까지 하려면 아래 값을 채웁니다.
- `admin_token`
- `service_token`

## 권장 실행 순서
1. `0) Health & Public`
2. `1) CryptoOrder Auth + Idempotency`
3. `2) Cross-Service: CryptoOrder Token -> RWA`
4. `3) Admin Checks (requires X-Admin-Token)`
5. `4) Internal API Checks (requires X-Service-Token)`

## 참고
- `2) Cross-Service`의 `Poll /me until wallet provisioned`는 CryptoOrder의 지갑 프로비저닝이 비동기(wallet-create 이벤트)일 수 있음을 고려한 폴링 요청입니다.
- 폴링은 Collection Runner에서 실행할 때 자동 재시도됩니다.
