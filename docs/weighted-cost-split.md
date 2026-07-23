# Weighted (fair) cost split

## Problem

Network-byte metrics arrive **per topic**, not per `(topic, consumer)`. When several principals consume one topic,
Kafka Cost Control historically split the topic's bytes **evenly** across the ACL-derived roster
(`readers` / `writers` context keys). So a topic with two consumers where one drives 99 % of the reads and the other
1 % billed each **50 %** — and, because ACLs are granted by wildcard/prefix, a consumer could be charged an equal
share of many topics it never actually read.

## What this does

For a metric configured for weighted split, the authoritative per-topic total `B(t)` (e.g. `sent_bytes`) is
distributed **proportionally to each consumer's measured usage** instead of evenly:

```
cost(p, t) = B(t) · weight(p, t) / Σ_q weight(q, t)
```

The total is always conserved (`Σ cost = B(t)`), so the aggregate bill is never distorted — only its distribution
across consumers changes.

## Where the weights come from (the signal waterfall)

Weights are fed in as a normalized metric — `kcc_consumption_weight`, tagged `topic`, `principal_id`, and an
optional `tier` — so the aggregator is **independent of the platform** that produced them. Any provider can emit it;
the higher tier wins per principal (`WeightTier`):

| Tier | Signal | Per-(topic, principal)? | Self-managed | Confluent Cloud |
|------|--------|:--:|:--:|:--:|
| **T1** `T1_CLIENT_TELEMETRY` | KIP-714 client telemetry (`bytes.consumed`, topic-labeled) | ✅ | ✅ broker `ClientTelemetry` receiver | ⚠️ only if the Metrics API exposes it |
| **T2** `T2_OFFSET_PROGRESS` | committed-offset advancement per `(group, topic)` | ✅ | ✅ `AdminClient.listConsumerGroupOffsets` | ✅ `consumer_lag_offsets` / AdminClient |
| **T3** `T3_PRINCIPAL_TOTAL` | per-principal totals (`response_bytes`) — no per-topic breakdown | ❌ | ⚠️ | ✅ already scraped |

**T2 is the cross-platform backbone.** Offset advancement *is* a record count, and record size cancels out of the
normalization (only `B(t)` carries absolute bytes), so T2 needs **only** committed-offset deltas per window — no
payload reads, no avg-record-size, no message-count metric. It works on both platforms and needs no client changes.
KIP-714 (T1) is a precision overlay where available. A provider that emits `kcc_consumption_weight` is not included
in this repo yet — it belongs in `kafka-scraper` (self-managed) / `confluent-agent` (Confluent Cloud).

## Handling a mixed fleet (Option B)

Real fleets mix clients that report and clients that don't. The rule (`ConsumptionWeightSplitter`):

- **Reporters** are billed by their measured ratio.
- **Non-reporters** (in the ACL roster but with no signal) are imputed the **mean reporter intensity** and pooled in
  (`imputation: MEAN_REPORTER`, the default). Alternatives: `ZERO` (reporters absorb everything) and
  `FALLBACK_PRINCIPAL` (residual concentrated on the `unknown` principal to pressure stragglers).
- It **degrades gracefully**: identical to the legacy even split at 0 % coverage, exact weighted split at 100 %,
  sensible in between — with **no config change** as coverage grows over the years.

Each `(topic, window)` also emits a `kcc_weighted_split_coverage` gauge (fraction of the bill backed by a real
measured signal vs imputed) — your confidence figure and dispute defense.

## Enabling it

Weighted split is **opt-in**. When a total metric is not listed, nothing changes. See the commented block in
`aggregator/src/main/resources/application.yaml`:

```yaml
cc:
  metrics:
    aggregations:
      kcc_consumption_weight: sum
    transformations:
      weightedSplit:
        confluent_kafka_server_sent_bytes: kcc_consumption_weight
      config:
        weightedSplit:
          rosterContextKey:
            confluent_kafka_server_sent_bytes: readers
          imputation:
            confluent_kafka_server_sent_bytes: MEAN_REPORTER
```

A total metric listed here is no longer emitted as a topic-level cost, nor split evenly — weighted split takes
precedence. The weight metric itself is only a signal: it is never priced or emitted as its own cost line.

## Testing with dummy data

The logic is split so it can be tested at two levels:

1. **Pure engine** — `ConsumptionWeightSplitterTest` and `TopicWeightAccumulatorTest` feed plain data (no Kafka) and
   pin down the math: the 99/1 case, equal usage, partial coverage / mean imputation, all imputation modes, the
   even-split fallback, tier precedence, cost conservation, and edge cases (zero/negative/no-roster).
2. **Full topology** — `MetricEnricherWeightedSplitTest` drives the real `MetricEnricher` topology with a
   `TopologyTestDriver`, feeding an authoritative total plus per-principal weight metrics and asserting the
   per-principal split out of the aggregated topic (including the coverage gauge and tier precedence end-to-end).

### Reproducing the motivating scenario by hand

Publish these Telegraf-shaped records to the raw topic (topic `spoud_orders`, roster `readers = heavy,light`):

```jsonc
// authoritative topic total
{ "name": "confluent_kafka_server_sent_bytes", "fields": {"gauge": 1000},
  "tags": {"topic": "spoud_orders"} }
// per-consumer weights (from a T1/T2 provider)
{ "name": "kcc_consumption_weight", "fields": {"gauge": 990},
  "tags": {"topic": "spoud_orders", "principal_id": "heavy", "tier": "T1"} }
{ "name": "kcc_consumption_weight", "fields": {"gauge": 10},
  "tags": {"topic": "spoud_orders", "principal_id": "light", "tier": "T1"} }
```

Result on the aggregated topic: `heavy → 990`, `light → 10` (instead of `500 / 500`). Drop the two weight records
and it falls back to the even `500 / 500`.
