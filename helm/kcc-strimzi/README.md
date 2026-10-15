# Kafka Cost Control Helm Chart for Strimzi

This helm chart deploys the Kafka Cost Control (KCC) appication on a Kubernetes cluster and configures it
to monitor a Strimzi Kafka cluster.
Specifically, it spins up telegraf, the strimzi context operator and the KCC application (aggregator).
Please install this chart in the same namespace as the Strimzi Kafka cluster.
To use the chart, first check `values.yaml` and adjust the configuration to your needs.
Then, install the chart with the following command:

```bash
# make sure that your current context is the namespace where the Strimzi Kafka cluster is deployed
helm install test .
```

The chart was developed/tested with Strimzi 0.44.0.

## Upgrading

Read the notes of every chart version between yours and the new one, oldest first, and preview
the change with `helm template` (or the `helm diff` plugin) before upgrading: a value that a later
version no longer accepts stops rendering with a message saying what replaced it.

An image you set in your values, such as `strimzi.contextOperator.image` for the `-v1beta2`
variant, stays as you set it: a new chart moves only the images you leave unset. Bump it to the
chart's `appVersion` yourself.

## Upgrading to 0.9.4

Bytes in and out no longer turn negative when a counter restarts, such as when a broker restarts
in a rolling update or a topic is deleted and created again: that period now counts 0 for the
series instead of minus its old total. Telegraf sends the value as `counter_non_negative_diff`
instead of `counter_diff`; the aggregator reads either.

## Upgrading to 0.9.3

The aggregator now also gives `strimzi.clusterName` as the cluster to metrics without `kafka_id`,
such as those collected before 0.9.2. To show the cluster on older data too, reprocess (see the
manual) after upgrading.

## Upgrading to 0.9.2

Metrics now really carry `kafka_id`. From 0.8.0 to 0.9.1 Telegraf's tag filter dropped it before
sending, so the aggregator never saw a cluster: KCC shows the cluster only for data collected after
this upgrade.

## Upgrading to 0.9.1

An upgrade now restarts Telegraf when its config changes. Before, Telegraf kept running with the
old config until its pod restarted for another reason: if you upgraded to 0.8.0 or 0.9.0, your
metrics may still lack `kafka_id` or use the old window. Upgrading to 0.9.1 restarts it once.

## Upgrading to 0.9.0

The images move from release 0.9.0 to 0.15.0. Three changes in between need a look:

- **Sign-in for everything.** With `aggregator.auth.mode: basic` (the default) every page and
  query now needs a sign-in, not only the changes. Set `aggregator.auth.adminPasswordSecret`, or
  the admin password is `admin`. Create that Secret before upgrading: the aggregator doesn't start
  without it. Use `none` only behind a proxy that authenticates.
- **Costs come from the OLAP database.** The aggregator no longer computes a cost per record: the
  `cost` field of the aggregated topics stays empty, and every page that shows a cost reads it
  from DuckDB. Set `aggregator.olapEnabled: true` to see costs. Enabling it fills the database
  only from then on: reprocess (see the manual) to fill it with the data the raw topic still holds.
- **New schema versions.** Pricing rules get a new schema version and bills a new schema. A
  registry that doesn't let clients register schemas needs them added first, from
  `aggregator/src/main/avro/`.

## Upgrading to 0.8.0

Telegraf now tags every metric with `kafka_id`, set to `strimzi.clusterName`. An aggregator from
release 0.14.0 on keeps it as the context value `cluster`, so one KCC can tell clusters apart;
older aggregators ignore it. Nothing to change in your values, but `strimzi.clusterName` is now the
name the cluster shows under in KCC.

## Upgrading to 0.7.0

`aggregator.aggregationWindowSize` and `telegraf.aggregationWindowSizeSeconds` were replaced by one
top-level `aggregationWindowSizeSeconds` (default `3600`). Telegraf reduces broker metrics to one
sample per window before the aggregator sums them, so two different values multiplied storage and
put traffic in the wrong windows. Rendering fails if either old key is still set. Move your value,
for example `aggregator.aggregationWindowSize: PT2M` becomes `aggregationWindowSizeSeconds: 120`.
