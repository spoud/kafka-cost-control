package io.spoud.kcc.operator;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class DefaultTestProfile implements QuarkusTestProfile {
    public static final String PREFIX = "(eu-west-1[.]|eu-east-1[.])?";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("quarkus.http.test-port", "0"),
                Map.entry("cc.strimzi.operator.topics.context-data", "context-data"),
                Map.entry("cc.strimzi.operator.context-regex-prefix", PREFIX),
                Map.entry("quarkus.operator-sdk.crd.apply", "true"), // the mock k8s server does not have strimzi CRDs, so we need to apply them
                // KafkaUserReconciler's background poll now runs unconditionally (see KafkaUserReconciler.reconcileNow()),
                // so it must be disabled here -- tests trigger reconciliation deterministically via direct calls instead.
                Map.entry("quarkus.scheduler.enabled", "false"),
                Map.entry("kafka.linger.ms", "0"),
                Map.entry("quarkus.log.category.\"io.spoud.kcc\".level", "DEBUG"),
                Map.entry("quarkus.operator-sdk.start-operator", "false"),
                Map.entry("quarkus.log.console.format", "[%X{testName}] %d{HH:mm:ss} %-5p [%c{2.}] (%t) %s%e%n"),
                Map.entry("quarkus.kafka.devservices.image-name", "redpandadata/redpanda:v25.1.1"),
                Map.entry("quarkus.kafka.devservices.provider", "redpanda")
        );
    }
}
