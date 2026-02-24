# Experiment Report Template

## 1) 실험 개요

- 실험 ID:
- 날짜(UTC):
- 브랜치/커밋:
- 목적:

## 2) 환경

- compose file:
- DB:
- Kafka topic/partition:
- Flink parallelism/sink parallelism:

## 3) 부하 프로파일

- mode: synthetic / replay
- rate:
- duration:
- total messages:

## 4) 장애 주입

- scenario:
- 주입 시점:
- 복구 완료 시점:

## 5) 핵심 결과(전/후)

| 지표 | Before | After | 개선율 |
|---|---:|---:|---:|
| ingest throughput |  |  |  |
| lag max |  |  |  |
| checkpoint p95 |  |  |  |
| backpressure level |  |  |  |
| top query mean |  |  |  |

## 6) 무결성 검증 결과

- processed_events duplicate:
- trades orphan:
- balances negative:
- 기타 이상:

## 7) 원인 분석

- 병목 포인트:
- 근거(로그/메트릭/쿼리플랜):

## 8) 조치

- 스키마/인덱스 변경:
- 파이프라인 변경:
- 운영 가드레일:

## 9) 재발 방지

- 모니터링/알람:
- runbook 업데이트:
- 다음 실험 과제:
