# Kafka Cost Control

This tool helps you understand the cost of your Kafka clusters. It gives you insights on what is costing you and where you could optimize your costs, by turning raw Kafka/broker metrics into per-topic and per-principal cost breakdowns.

## How it works

```
Scrapers → raw Kafka topic → Aggregator (Kafka Streams) → aggregated Kafka topic → Frontend (GraphQL)
                                       ↓
                                  DuckDB (optional OLAP)
```

- **Scrapers** (`kafka-scraper`, `confluent-agent`) collect topic/partition/schema metrics from your Kafka cluster(s) and publish them as Telegraf-format JSON.
- **Aggregator** (`aggregator/`) is the core Quarkus/Kafka Streams service: it enriches raw metrics with context (via `context-data`), windows and reduces them, joins them against configurable `pricing-rules`, and produces windowed cost data.
- **Frontend** (`frontend/`) is an Angular SPA that queries the aggregator's GraphQL API to visualize costs.
- **Strimzi Operator** (`strimzi-operator/`) watches `KafkaTopic`/`KafkaUser` CRDs and auto-publishes context-data records from resource annotations, for Strimzi-managed clusters.
- **Kafka Connect** (`kafka-connect/`) provides a JDBC sink image to export aggregated data to TimescaleDB/PostgreSQL.

## Getting started

```bash
cd aggregator
docker compose up             # local Kafka (KRaft) + Schema Registry
./mvnw quarkus:dev            # dev mode with hot reload, Dev UI at :8080/q/dev/
```

```bash
cd frontend
npm install
npm start                     # dev server (talks to the aggregator's GraphQL API)
```

See each module's own README/build files for the full set of commands (native builds, tests, etc.).

## Documentation
 - [User manual](https://spoud.github.io/kafka-cost-control/#_user_manual)
 - [Installation guide](https://spoud.github.io/kafka-cost-control/#section-installation)
 - [Architecture](https://spoud.github.io/kafka-cost-control/#_architecture)

## Demonstration
The demo application shows you what's possible with Kafka Cost Control. We used our kafka test cluster, so the
data don't mean a lot. But it gives you a good idea of what you can achieve.

 - [Grafana Dashboard](https://kafka-cost-control-grafana-demo.sdm.spoud.io/d/b56a35cd-5052-496e-a534-7181836c3e0b/montly-usage) user and password is **demo:demo**
 - [Kafka Cost Control UI](https://kafka-cost-control-demo.sdm.spoud.io/)
