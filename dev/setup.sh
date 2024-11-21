#!/bin/sh

BASEDIR=$(dirname "$0")
cd $BASEDIR

KINDCLUSTERNAME=kcc-dev

echo "-------------------------------------"
echo "Setting up the kind cluster"
kind get clusters | grep $KINDCLUSTERNAME >/dev/null
if [ $? -ne 0 ]; then
  kind create cluster --config=kind-cluster.yaml
else
  kubectl config set-context kind-$KINDCLUSTERNAME
fi

echo "-------------------------------------"
echo "Setting up strimzi cluster operator"
helm list 2>/dev/null | grep strimzi-cluster-operator 2>/dev/null 1>/dev/null
if [ $? -ne 0 ]; then
  helm install strimzi-cluster-operator oci://quay.io/strimzi-helm/strimzi-kafka-operator --set "watchAnyNamespace=true"
fi

echo "-------------------------------------"
echo "Setting up Kafka cluster"
kubectl get namespace kafka >/dev/null
if [ $? -ne 0 ]; then
  kubectl create namespace kafka
fi

kubectl apply -f kafka-cluster.yaml
kubectl wait -n kafka --for=condition=Ready --timeout=2m kafka kafka-cluster

kubectl apply -f topic.yaml
kubectl wait -n kafka --for=condition=Ready kafkatopic metrics-raw-telegraf-dev
