ROOT := /Users/kanghyoseung/Desktop/project/rwa
COMPOSE_BASE := $(ROOT)/infra/docker-compose.yml
COMPOSE_EXP := $(ROOT)/experiment/infra/docker-compose.experiment.yml

.PHONY: up up-observe down reset reset-full load replay metrics verify fault cycle apply-indexes apply-partitions

up:
	docker compose -f $(COMPOSE_BASE) up -d

up-observe:
	docker compose -f $(COMPOSE_BASE) -f $(COMPOSE_EXP) up -d

down:
	docker compose -f $(COMPOSE_BASE) -f $(COMPOSE_EXP) down

reset:
	$(ROOT)/experiment/scripts/reset_experiment_state.sh --mode read-model --compose-file $(COMPOSE_BASE)

reset-full:
	$(ROOT)/experiment/scripts/reset_experiment_state.sh --mode full --compose-file $(COMPOSE_BASE)

load:
	$(ROOT)/experiment/scripts/loadgen_chain_logs.sh --mode synthetic --rate 1000 --duration-sec 120 --compose-file $(COMPOSE_BASE)

replay:
	$(ROOT)/experiment/scripts/loadgen_chain_logs.sh --mode replay --rate 1000 --duration-sec 120 --compose-file $(COMPOSE_BASE)

metrics:
	$(ROOT)/experiment/scripts/collect_runtime_metrics.sh --compose-file $(COMPOSE_BASE) --label manual

verify:
	$(ROOT)/experiment/scripts/verify_consistency.sh --compose-file $(COMPOSE_BASE)

fault:
	$(ROOT)/experiment/scripts/inject_faults.sh --compose-file $(COMPOSE_BASE) --scenario all

cycle:
	$(ROOT)/experiment/scripts/run_benchmark_cycle.sh --compose-file $(COMPOSE_BASE) --rate 2000 --duration-sec 180 --fault-scenario all

apply-indexes:
	$(ROOT)/experiment/scripts/apply_perf_candidates.sh --compose-file $(COMPOSE_BASE) --indexes

apply-partitions:
	$(ROOT)/experiment/scripts/apply_perf_candidates.sh --compose-file $(COMPOSE_BASE) --partition
