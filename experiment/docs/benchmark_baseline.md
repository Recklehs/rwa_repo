# Benchmark Baseline

## 1) 목표 KPI/SLO

- 인덱서 처리량: `>= 10,000 events/sec` (synthetic 기준)
- Kafka consumer lag 회복: 장애 주입 후 `5분 이내` baseline 복귀
- Flink checkpoint duration: `P95 < 30s`
- API 조회 지연(부하 병행 시):
  - `/market/listings`: `P95 < 150ms`
  - `/users/{userId}/holdings`: `P95 < 200ms`
- 무결성:
  - `processed_events` 중복 0건
  - `trades` orphan 0건
  - `balances.amount < 0` 0건

## 2) 테스트 데이터 프로파일

- S: 1,000 msg/s, 5분
- M: 5,000 msg/s, 10분
- L: 10,000 msg/s, 15분
- XL: 20,000 msg/s, 15분 + 장애 주입(`all`)

## 3) 전/후 비교 포맷(고정)

| 항목 | Before | After | 개선율 |
|---|---:|---:|---:|
| ingest throughput (msg/s) |  |  |  |
| kafka lag max |  |  |  |
| checkpoint p95 (ms) |  |  |  |
| postgres CPU (%) |  |  |  |
| top query mean (ms) |  |  |  |
| holdings p95 (ms) |  |  |  |
| listings p95 (ms) |  |  |  |
| consistency violations |  |  |  |

## 4) 필수 수집 산출물

- `collect_runtime_metrics.sh` pre/post snapshot
- `verify_consistency.sh` 결과 파일
- loadgen 로그(`sent`, `effective_rate`)
- 장애 주입 로그(시각, 시나리오)

## 5) 합격 기준

- KPI 중 `무결성` 항목 전부 충족
- 처리량/지연 목표 중 최소 70% 달성
- 장애 주입 후 자동 복구 및 데이터 정합성 유지
