package io.spoud.kcc.operator;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class DefaultTestProfile implements QuarkusTestProfile {
    public static final String PREFIX = "(eu-west-1[.]|eu-east-1[.])?";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "cc.strimzi.operator.topics.context-data", "context-data",
                "cc.strimzi.operator.context-regex-prefix", PREFIX,
                "quarkus.operator-sdk.crd.apply", "true", // the mock k8s server does not have strimzi CRDs, so we need to apply them
                "kafka.linger.ms", "0",
                "quarkus.log.category.\"io.spoud.kcc\".level", "DEBUG",
                "quarkus.operator-sdk.start-operator", "false",
                "quarkus.log.console.format", "[%X{testName}] %d{HH:mm:ss} %-5p [%c{2.}] (%t) %s%e%n"
        );
    }
}
