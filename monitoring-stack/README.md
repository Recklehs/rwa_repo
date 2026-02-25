# Monitoring Stack (minimal)

Minimal local monitoring stack for the two deployed Spring servers.

This stack runs:
- Prometheus (scrape + store)
- Grafana (query + dashboard)

## Prerequisites

- Docker
- Docker Compose plugin (`docker compose`)
- Your current public IP must be included in both services' `loadBalancerSourceRanges`.

## Start

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/monitoring-stack
cp prometheus/prometheus.example.yml prometheus/prometheus.yml
# edit prometheus/prometheus.yml targets for your current LB IPs
docker compose up -d
```

## Access

- Prometheus: <http://localhost:9090>
- Grafana: <http://localhost:3000>
  - user: `admin`
  - pass: `admin`

A default dashboard is auto-provisioned:
- `RWA / RWA + CryptoOrder Overview`
- `RWA / RWA + CryptoOrder Load Test`
- `RWA / RWA + CryptoOrder Load Test v2`

## Quick checks

Prometheus targets page:
- <http://localhost:9090/targets>

Both targets should become `UP`.

## Stop

```bash
docker compose down
```

To remove stored data too:

```bash
docker compose down -v
```

## Notes

- Copy `prometheus/prometheus.example.yml` to `prometheus/prometheus.yml` and set targets before starting.
- Kubernetes optional panels in v2 dashboard require cAdvisor/kube-state-metrics series.
- If server LB IP changes, update `prometheus/prometheus.yml` targets and restart Prometheus:

```bash
docker compose restart prometheus
```
