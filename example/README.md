# Kafka Cost Control Demo Setup

This repo sets up an example Kubernetes environment for Kafka Cost Control (https://github.com/spoud/kafka-cost-control).

## Requirements

Have a Kubernetes cluster with Strimzi installed (we assume that the Strimzi operator is running in the `kafka` namespace).

## Setup

1. Create a Kafka cluster with a metrics config:
    ```bash
    kubectl apply -f manifests/kafka.yaml -n kafka
    ```
1. Install a schema registry:
    ```bash
    kubectl apply -f manifests/schema-registry.yaml -n kafka
    ```
1. Install cost control from the helm chart:
    ```bash
    helm install ctf-kcc ../helm/kcc-strimzi --namespace kafka -f kcc/values.yaml
    ```
1. Install prometheus
    ```bash
    kubectl apply -f manifests/prometheus.yaml -n kafka
    ```
1. Install grafana operator
    ```bash
    helm upgrade -i grafana-operator oci://ghcr.io/grafana/helm-charts/grafana-operator --version v5.18.0 --namespace kafka
    ```
1. Install grafana instance
    ```bash
    kubectl apply -f manifests/grafana.yaml -n kafka
    kubectl apply -f manifests/grafana-dashboard.yaml -n kafka
    ```
1. Now install some synth-clients to add some usage to the Kafka cluster:
    ```bash
    kubectl apply -f manifests/web-analytics.yaml -n kafka
    kubectl apply -f manifests/ecommerce.yaml -n kafka
    ```

