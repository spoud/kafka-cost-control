# Kafka Cost Control Helm Chart for Strimzi

This helm chart deploys the Kafka Cost Control (KCC) appication on a Kubernetes cluster and configures it
to monitor a Strimzi Kafka cluster.
Specifically, it spins up telegraf, timescaledb, the strimzi context operator and the KCC application (aggregator).
Furthermore, it configures a kafka connect instance to ingest outputs of the aggregator into the timescaledb.
Please install this chart in the same namespace as the Strimzi Kafka cluster.
To use the chart, first check `values.yaml` and adjust the configuration to your needs.
Then, install the chart with the following command:

```bash
# make sure that your current context is the namespace where the Strimzi Kafka cluster is deployed
helm install test .
```

The chart was developed/tested with Strimzi 0.44.0.
