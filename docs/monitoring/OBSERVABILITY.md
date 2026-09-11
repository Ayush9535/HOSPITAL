# Observability & Monitoring

Runtime visibility for HMS — metrics, health, alerting, and the golden signals an on-call engineer
needs to detect and diagnose production issues on a 24×7 hospital system. This is the
application-instrumentation + config layer; standing up Prometheus/Grafana/Alertmanager is an
operator step (this doc tells you exactly how).

> Complements the existing [health verification](../deployment/DEPLOYMENT.md#health-verification)
> and [deployment manifests](../deployment/DEPLOYMENT.md#deployment-manifest-audit-record).

---

## What the app exposes

| Endpoint | Auth | Purpose |
|---|---|---|
| `/api/public/health` | public | liveness probe (deploy gate, uptime checks) |
| `/actuator/health` | public | readiness (aggregates DB, Redis, disk) |
| `/actuator/info` | public | build + git provenance (version, commit, branch) |
| `/actuator/prometheus` | **authenticated** | Prometheus metrics scrape |
| `/actuator/metrics` | **authenticated** | ad-hoc metric browsing |

Micrometer auto-instruments, with **no business-code changes**:
- **HTTP** — `http_server_requests_seconds_*` (rate, errors by status, latency histograms → p95/p99)
- **JVM** — heap/non-heap, GC, threads, classes
- **Database** — `hikaricp_connections_*` (active/idle/max/pending)
- **Web server** — Tomcat sessions/threads
- **Flyway** — migration state
Every meter is tagged `application` + `environment` (via `MetricsConfig`) so one Prometheus/Grafana
serves staging and production.

Latency histograms + SLO buckets are enabled (`application.properties`) so p95/p99 and
error-budget queries work out of the box. Add custom business metrics later with Micrometer
`Counter`/`Timer` or `@Timed` (the `TimedAspect` is wired) — e.g. patients registered, bills raised.

---

## Securing the scrape (important — metrics are not public)

`/actuator/prometheus` stays **authenticated** (it is *not* in the SecurityConfig `permitAll` list),
so it is never world-readable. Pick one model:

- **(A) Same-host + Nginx allow-list (recommended, no app change):** run Prometheus on the VPS and
  scrape `127.0.0.1:8080`. Add an Nginx `location = /actuator/prometheus { allow 127.0.0.1; deny all; }`
  (and the same for `/actuator/metrics`) so the path is only reachable locally.
- **(B) Separate management port:** set `MANAGEMENT_SERVER_PORT` on the service so Actuator binds a
  port Nginx doesn't expose; scrape that port. (Note: this also moves `/actuator/health`/`info` to
  that port — update any external probes accordingly.)

Never add these endpoints to `permitAll` / expose them through the public vhost.

---

## Stand up the stack (operator steps)

1. **Prometheus** — merge [`monitoring/prometheus/scrape-config-example.yml`](../../monitoring/prometheus/scrape-config-example.yml)
   into `prometheus.yml`; load [`monitoring/prometheus/hms-alerts.yml`](../../monitoring/prometheus/hms-alerts.yml)
   via `rule_files`.
2. **Grafana** — import [`monitoring/grafana/hms-dashboard.json`](../../monitoring/grafana/hms-dashboard.json)
   (request rate, 5xx %, p95/p99 latency, JVM heap %, DB pool). Select your Prometheus datasource.
3. **Alertmanager** — route the alerts below to Slack/email/on-call (reuse the deploy Slack webhook
   if you like).
4. **Host metrics (optional but recommended)** — add `node_exporter` for CPU/mem/disk host alerts
   (the deploy-time `verify-deployment.sh` already checks these per deploy).

---

## Alerts shipped

| Alert | Severity | Fires when |
|---|---|---|
| `HMSInstanceDown` | critical | scrape target down >1m |
| `HMSHighErrorRate` | critical | 5xx rate >5% for 5m |
| `HMSDBDown` | critical | no DB connections for 2m |
| `HMSHighLatencyP95` | warning | p95 >1s for 10m |
| `HMSHighHeapUsage` | warning | heap >90% for 10m |
| `HMSDBPoolNearExhaustion` | warning | pool >90% for 5m |

Tune thresholds against real baselines after a week of data.

---

## The four golden signals (where to look)
- **Latency** — dashboard p95/p99; `HMSHighLatencyP95`.
- **Traffic** — dashboard request rate.
- **Errors** — dashboard 5xx %; `HMSHighErrorRate`.
- **Saturation** — JVM heap %, DB pool, host CPU/mem/disk (node_exporter).

## What this does NOT include (future / infra scope)
Distributed tracing (OpenTelemetry), centralized log aggregation (Loki/ELK), synthetic monitoring,
and long-term metric storage/HA for Prometheus itself. These need infrastructure beyond the current
single VPS and are tracked in the [Production Readiness Review](../PRODUCTION_READINESS_REVIEW.md).

## Manual configuration required
- Enable a secure scrape model (A or B above).
- Deploy Prometheus + Grafana + Alertmanager (and optionally node_exporter) on/near the VPS.
- Wire Alertmanager to your notification channel and set an on-call rotation.
