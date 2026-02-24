# Experiment Runbook

## 0) 사전 조건

- Docker / Docker Compose 동작
- `infra` 서비스 기동 가능 (`kafka`, `postgres`, `flink`)
- `jq`, `curl` 설치

## 1) 인프라 기동

```bash
docker compose -f infra/docker-compose.yml up -d
```

관측 포함 기동:

```bash
docker compose \
  -f infra/docker-compose.yml \
  -f experiment/infra/docker-compose.experiment.yml \
  up -d
```

## 2) 초기화

read-model만 초기화:

```bash
./experiment/scripts/reset_experiment_state.sh --mode read-model
```

전체 초기화:

```bash
./experiment/scripts/reset_experiment_state.sh --mode full
```

## 3) 성능 후보 적용(선택)

```bash
./experiment/scripts/apply_perf_candidates.sh --indexes
./experiment/scripts/apply_perf_candidates.sh --partition
```

## 4) 부하 주입

synthetic:

```bash
./experiment/scripts/loadgen_chain_logs.sh --mode synthetic --rate 5000 --duration-sec 300
```

replay:

```bash
./experiment/scripts/loadgen_chain_logs.sh --mode replay --replay-file experiment/workloads/sample_chain_logs.ndjson --rate 2000 --duration-sec 180
```

## 5) 장애 주입

```bash
./experiment/scripts/inject_faults.sh --scenario all
```

개별 시나리오:

```bash
./experiment/scripts/inject_faults.sh --scenario flink-restart
./experiment/scripts/inject_faults.sh --scenario postgres-restart
./experiment/scripts/inject_faults.sh --scenario kafka-rebalance
```

## 6) 지표/정합성 수집

```bash
./experiment/scripts/collect_runtime_metrics.sh --label post-run
./experiment/scripts/verify_consistency.sh --out experiment/results/consistency_latest.txt
```

## 7) 원샷 실행

```bash
./experiment/scripts/run_benchmark_cycle.sh \
  --rate 5000 \
  --duration-sec 300 \
  --fault-scenario all
```

## 8) 반복 테스트 시 권장 루프

1. `reset_experiment_state.sh`
2. `collect_runtime_metrics.sh --label pre`
3. `loadgen_chain_logs.sh`
4. `inject_faults.sh` (중간)
5. `collect_runtime_metrics.sh --label post`
6. `verify_consistency.sh`
7. 결과를 `docs/report_template.md`에 기록
