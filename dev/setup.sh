#!/bin/sh

BASEDIR=$(dirname "$0")
cd $BASEDIR

KINDCLUSTERNAME=kcc-dev

echo "-------------------------------------"
echo "Setting up the kind cluster"
kind get clusters | grep $KINDCLUSTERNAME >/dev/null
if [ $? -ne 0 ]; then
  kind create cluster -n $KINDCLUSTERNAME
else
  kubectl config set-context kind-$KINDCLUSTERNAME
fi

echo "-------------------------------------"
echo "Setting up strimzi cluster operator"
helm list 2>/dev/null | grep strimzi-cluster-operator 2>/dev/null 1>/dev/null
if [ $? -ne 0 ]; then
  helm install strimzi-cluster-operator oci://quay.io/strimzi-helm/strimzi-kafka-operator --set "watchAnyNamespace=true"
  echo "Waiting for strimzi cluster operator..."
  kubectl wait --for=condition=available --timeout=2m deployment strimzi-cluster-operator
fi

echo "-------------------------------------"
echo "Setting up Kafka cluster"
kubectl get namespace kafka 2>/dev/null 1>/dev/null
if [ $? -ne 0 ]; then
  kubectl create namespace kafka
fi

kubectl apply -f kafka-cluster.yaml
echo "Waiting for Kafka cluster..."
kubectl wait -n kafka --for=condition=Ready --timeout=5m kafka kafka-cluster

kubectl apply -f topic.yaml
echo "Waiting for Kafka topics..."
kubectl wait -n kafka --for=condition=Ready kafkatopic metrics-raw-telegraf-dev
