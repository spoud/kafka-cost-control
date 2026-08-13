# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

`.mvn/` (the Maven wrapper jar/config) is gitignored repo-wide, so `./mvnw` in a fresh checkout of aggregator/kafka-scraper/strimzi-operator fails with "Could not find or load main class org.apache.maven.wrapper.MavenWrapperMain". Use a system-installed `mvn` instead (e.g. `mvn quarkus:dev`) unless you've bootstrapped the wrapper yourself.

### Aggregator (Quarkus/Java 21)
```bash
cd aggregator
./mvnw clean package          # build
./mvnw quarkus:dev            # dev mode (hot reload, Dev UI at :8080/q/dev/)
./mvnw test                   # run all tests
./mvnw test -Dtest=MetricEnricherTest  # run a single test class
./mvnw package -Dnative       # native image build (also available in kafka-scraper, strimzi-operator)
```
Integration tests (`*IT.java`) require Docker (Testcontainers spins up Kafka + Schema Registry).

### Kafka Scraper (Quarkus/Java 21)
```bash
cd kafka-scraper
./mvnw clean package
./mvnw quarkus:dev
./mvnw test                   # run all tests
```

### Strimzi Operator (Quarkus/Java 21)
```bash
cd strimzi-operator
./mvnw compile quarkus:dev    # runs against the current kubectl context
./mvnw test                   # run all tests (Testcontainers Kafka + mocked Kubernetes client)
```

### Frontend (Angular 21)
```bash
cd frontend
npm install
npm start                     # dev server
npm run build                 # production build
npm test                      # run tests (ChromeHeadless)
npm run lint
npm run format                 # prettier --write
npm run generate              # regenerate GraphQL types from schema (requires aggregator running on :8080)
npm run update-schema         # fetch schema.graphql from running aggregator
```
`src/generated/graphql/` is git-ignored — it's build output from `npm run generate`, not checked in.

### Confluent Agent (Python 3.13, uv)
```bash
cd confluent-agent
uv sync
uv run main.py
uv run python -m pytest       # requires Docker
```

### Kafka Connect
```bash
cd kafka-connect
docker-compose build connect  # build custom Connect image with the JDBC connector
docker-compose push connect
```

### Local Dev Infrastructure
```bash
cd aggregator
docker compose up             # starts Kafka (KRaft) on :9092 + Schema Registry on :8081
```

## Architecture

This is a multi-module system that assigns costs to Kafka topic/principal usage.

### Data Flow

```
Scrapers → raw Kafka topic → Aggregator (Kafka Streams) → aggregated Kafka topic → Frontend (GraphQL)
                                       ↓
                                  DuckDB (optional OLAP)
```

1. **Scrapers** feed the raw input topic with metrics in Telegraf JSON format (`{name, fields, tags, timestamp}`), but the two implementations get there differently:
   - `kafka-scraper`: polls Kafka AdminClient for topic partition counts and Schema Registry for per-topic schema counts, and exposes these only as Prometheus metrics via Micrometer at `/q/metrics`. It does **not** publish to Kafka itself — a separate Telegraf agent (deployed as a sidecar, see `helm/kcc-strimzi/templates/telegraf*.yaml`) scrapes that endpoint and publishes the Telegraf JSON to the raw topic.
   - `confluent-agent`: polls the Confluent Cloud telemetry API hourly (Python) and produces Telegraf JSON directly to Kafka itself.

2. **Aggregator** (`aggregator/`) is the core service. Its Kafka Streams topology (`MetricEnricher`) does:
   - Reads raw metrics → looks up context via `GlobalKTable` on the `context-data` topic
   - Context matching uses regex against the `topic` or `principal_id` tag; matched context key-values are attached to the metric
   - Metrics are rekeyed, grouped, and windowed (default 1-hour tumbling window), then reduced (`MetricReducer`)
   - Windowed aggregates are left-joined with a `KTable` from the `pricing-rules` topic to compute cost: `baseCost + costFactor * value`
   - Output goes to the `aggregated` topic (Avro) and a `aggregated-table-friendly` topic (maps serialized to JSON strings for JDBC sink)
   - Optionally inserted into DuckDB (`cc.olap.enabled=true`) for CSV/JSONL export via `/olap/export`

3. **Frontend** (`frontend/`) is an Angular 21 SPA with Angular Material UI. It uses Apollo Angular for GraphQL queries to the aggregator (`/graphql`). GraphQL types in `src/generated/graphql/` are auto-generated from the schema via `npm run generate`. State management uses NgRx Signals.

4. **Strimzi Operator** (`strimzi-operator/`) watches `KafkaTopic` and `KafkaUser` CRDs and automatically publishes context-data records based on annotations prefixed with `spoud.io/kcc-context.`.

5. **Kafka Connect** (`kafka-connect/`) provides a custom Docker image with a JDBC connector to sink aggregated data to TimescaleDB (PostgreSQL).

### Key Concepts

**Context Data**: Rules stored in the `context-data` topic (backed by GlobalKTable). Each rule has an `entityType` (TOPIC or PRINCIPAL), a `regex`, optional `validFrom`/`validUntil` timestamps, and a map of context key-values. The regex is matched against the topic name or principal ID tag in incoming metrics. Regex capturing groups can be referenced in context values (e.g., `$1`). The `ContextDataRepository` caches the store for 10 seconds.

**Pricing Rules**: Stored in the `pricing-rules` topic (backed by KTable), keyed by initial metric name. Formula: `cost = baseCost + costFactor * value`.

**Metric Splitting**: Via `cc.metrics.transformations.splitMetricAmongPrincipals`, a topic metric can be split into multiple PRINCIPAL-type metrics by reading a comma-separated list from a context key. Value is divided evenly. Configurable handling for missing keys: `PASS_THROUGH`, `ASSIGN_TO_FALLBACK`, or `DROP`.

### Avro Schemas

All Kafka messages between services use Avro with Confluent/Apicurio Schema Registry. Schemas live in `aggregator/src/main/avro/`; `context-data.avsc` and `entity-type-enum.avsc` are duplicated in `strimzi-operator/src/main/avro/` since the operator also produces context-data records. Key schemas:
- `context-data.avsc`, `pricing-rule.avsc` — control plane inputs
- `aggregated-data.avsc` / `aggregated-data-key.avsc` — pre-windowed internal stream record and its output-topic key
- `aggregated-data-windowed.avsc` — primary windowed output (includes `cost`, `context`, `tags`)
- `aggregated-data-table-friendly.avsc` — JDBC-friendly variant (maps as JSON strings)

### Configuration

The aggregator uses SmallRye Config with the `cc.*` prefix (`CostControlConfigProperties`). Key settings in `aggregator/src/main/resources/application.yaml`:
- `cc.topics.*` — topic names for all channels
- `cc.olap.enabled` — enables DuckDB; `cc.olap.database.url` defaults to in-memory `jdbc:duckdb:`
- `cc.metrics.aggregations` — per-metric aggregation function (default sum, `max` for retained bytes)
- `cc.admin-password` — password for the `admin` user (HTTP Basic Auth on GraphQL mutations)

**OLAP/DuckDB is not a passive mirror of the `aggregated` topic.** `AggregatedMetricsRepository.insertRow` is only called as the *running* Kafka Streams app processes records live — enabling `cc.olap.enabled` on an app that already has committed consumer offsets (e.g. flipping it on in an existing `quarkus:dev` session) backfills nothing, since there's no new input to process. Symptom: OLAP-backed queries (`metricContextKeys`, `/olap/export`, cost-overview Group By) return empty even though `context-data`/`aggregated` topics have plenty of data. Fix: trigger `POST /api/v1/kafka-stream/reprocess?areYouSure=yes` (admin/`cc.admin-password` basic auth) to reset the consumer group offsets and replay the raw topic from the start. That call resets Kafka Streams state and then does `Quarkus.asyncExit()` to force a full restart — under `mvn quarkus:dev` this has been observed to leave the process stuck (CDI/SmallRye context manager null, every request 500s, 0% CPU) rather than cleanly reloading, requiring a manual kill and `mvn quarkus:dev` relaunch. Once it comes back up cleanly, reprocessing ~1M raw records took a couple of minutes locally.

### APIs

The aggregator exposes:
- **GraphQL** at `/graphql` and GraphQL UI at `/graphql-ui` — primary interface for the frontend
- **REST** at `/api/v1/metrics/*`, `/api/v1/pricing-rules/*`, `/api/v1/context-data/*` — same queries as GraphQL, also available as REST endpoints
- **REST** at `/api/v1/kafka-stream/reprocess` — triggers Kafka Streams reprocessing
- **OLAP export** at `/olap/export` — CSV or JSONL bulk export (requires `cc.olap.enabled=true`)
- **Health** via SmallRye Health at `/q/health`
- **Metrics** via Micrometer/Prometheus at `/q/metrics`

### Releases

Tags matching `v*.*.*` on master trigger CI builds and container image pushes. Use `git tag vX.Y.Z && git push --tags` to release.

### Deploying (Demo environment)

Deployment manifests live in a sibling repo, `kafka-cost-control-deployments` (checked out alongside this one, e.g. `../kafka-cost-control-deployments`), not in this repo. Its `deployment/kafka-cost-control/demo` kustomize overlay references this repo's `deployment/kafka-cost-control/base` via a relative path (`../../../../kafka-cost-control/deployment/kafka-cost-control/base`), so both repos must be checked out side by side.

To deploy a new release to the demo environment after tagging (see Releases above) and confirming CI has finished pushing images (`gh run list`):

1. In `kafka-cost-control-deployments`, bump the three tracked image tags in `deployment/kafka-cost-control/demo/kustomization.yaml` (`spoud/kafka-cost-control`, `spoud/kafka-cost-control-ui`, `spoud/kafka-cost-control-connect`) to the new version, then commit and push directly to `master` — this repo has no PR convention for these bumps, see prior commits like `pin demo deployment images to 0.6.3 instead of latest`.
2. Fetch secrets (requires LastPass access): `lpass login <email>` then `./fetch-secrets.sh` from the repo root — this writes `deployment/kafka-cost-control/demo/.env`, which the overlay's `secretGenerator` needs and which is gitignored, so nobody but you can produce it.
3. Point `kubectl` at the target cluster (demo uses context `spoud` → `kubernetes.sdm.spoud.io`), then preview before applying: `kubectl kustomize deployment/kafka-cost-control/demo | kubectl diff -f -`.
4. Apply: `kubectl apply -k deployment/kafka-cost-control/demo` (namespace `kafka-cost-control-demo`).
5. Verify: `kubectl -n kafka-cost-control-demo rollout status statefulset/kafka-cost-control` (and the `kafka-connect`/`kafka-cost-control-ui` deployments), then hit `/q/health/ready` on the aggregator pod.

Note the aggregator is a `StatefulSet` (`kafka-cost-control`), while the UI and Kafka Connect are `Deployment`s — all three come from the base manifest's `:latest` image tag, overridden per-environment by the overlay's `images:` block. If an environment's overlay hasn't been re-applied in a while, its running image can silently lag behind what's pinned in git — always check the live image with `kubectl get statefulset,deploy -o jsonpath=...` before assuming the overlay reflects reality.
