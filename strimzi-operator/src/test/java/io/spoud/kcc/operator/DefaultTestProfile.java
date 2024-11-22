package io.spoud.kcc.operator;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class DefaultTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "cc.strimzi.operator.topics.context-data", "context-data",
                "quarkus.operator-sdk.crd.apply", "true" // the mock k8s server does not have strimzi CRDs, so we need to apply them
        );
    }
}
