# wallet-create E2E Postman 실행 가이드

## 파일
- 컬렉션: `postman/CryptoOrder-Custody-WalletCreate-E2E.postman_collection.json`
- 환경: `postman/CryptoOrder-Custody-WalletCreate-E2E.postman_environment.json`
- Newman 스크립트: `postman/run-wallet-create-e2e-newman.sh`

## 검증 목적
- CryptoOrder 회원가입 시 `wallet-create` 이벤트가 발행되는지
- Custody(`custody-consumer-group`)가 소비 후 지갑을 정상 생성하는지
- 최종적으로 `/me`, `/me/wallet`에서 지갑이 조회되는지

## 실행 순서
1. 두 서버와 Kafka를 먼저 실행합니다.
2. Postman에 컬렉션/환경 파일을 import 합니다.
3. 환경을 선택하고 Runner에서 컬렉션 전체를 실행합니다.
4. 2번 요청(`/me 폴링`)은 지갑이 준비될 때까지 자동 반복합니다.

## 성공 기준
- 1) 회원가입 응답 200 + accessToken/userId 수신
- 2) 폴링 중 최종적으로 `/me`가 200 + address 반환
- 3) `/me/wallet` 200 + address 일치

## 실패 시 확인 포인트
- 2번 요청이 최대 폴링 횟수까지 `WALLET_NOT_PROVISIONED`
  - Kafka 접속 설정, topic 이름(`wallet-create`), consumer group(`custody-consumer-group`) 확인
  - Custody 로그에서 consumer 예외/DLQ 여부 확인
