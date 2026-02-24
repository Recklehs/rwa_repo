# RWA Experiment Kit

이 폴더는 반복 성능 실험/장애 주입/무결성 검증을 위한 독립 실행 패키지입니다.

## 구성

- `scripts/`
  - `reset_experiment_state.sh`: 원클릭 초기화(DB/Kafka/checkpoint)
  - `loadgen_chain_logs.sh`: `chain.logs.raw` 초당 N건 부하/리플레이
  - `collect_runtime_metrics.sh`: Postgres/Kafka/Flink 지표 스냅샷 수집
  - `inject_faults.sh`: Flink/Postgres 재시작, Kafka rebalance 주입
  - `run_benchmark_cycle.sh`: 리셋→부하→장애→수집→검증 일괄 실행
  - `verify_consistency.sh`: read-model 일관성 SQL 실행
  - `apply_perf_candidates.sh`: 성능 후보 SQL 적용
  - `enable_db_observability.sh`: `pg_stat_statements`, `auto_explain` 설정 적용
- `sql/`
  - `reset_read_model.sql`, `reset_full.sql`
  - `verify_consistency.sql`
  - `migrations/` (partition/partial index/observability 후보)
- `observability/`
  - Prometheus/Grafana 설정
- `infra/docker-compose.experiment.yml`
  - 관측 확장 오버레이 compose
- `workloads/sample_chain_logs.ndjson`
  - replay 샘플 이벤트
- `docs/`
  - 벤치마크 기준/런북/리포트 템플릿

## 빠른 시작

```bash
cd /Users/kanghyoseung/Desktop/project/rwa

# 1) (선택) 관측 스택 포함 실행
# 기본 infra + 실험 오버레이를 같이 사용

docker compose \
  -f infra/docker-compose.yml \
  -f experiment/infra/docker-compose.experiment.yml \
  up -d

# 2) read-model 초기화
./experiment/scripts/reset_experiment_state.sh --mode read-model

# 3) synthetic 부하 (초당 1000건, 120초)
./experiment/scripts/loadgen_chain_logs.sh --mode synthetic --rate 1000 --duration-sec 120

# 4) 지표 스냅샷 수집
./experiment/scripts/collect_runtime_metrics.sh --label after-load

# 5) 무결성 검증
./experiment/scripts/verify_consistency.sh
```

## 원클릭 실험 사이클

```bash
./experiment/scripts/run_benchmark_cycle.sh \
  --rate 2000 \
  --duration-sec 180 \
  --fault-scenario all
```

결과는 `experiment/results/`에 저장됩니다.
