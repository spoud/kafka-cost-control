package io.spoud.kcc.operator;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Same as {@link DefaultTestProfile}, but configured to read KafkaUser/KafkaTopic via the v1beta2 CRD API. */
public class V1Beta2TestProfile extends DefaultTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        var overrides = new java.util.HashMap<>(super.getConfigOverrides());
        overrides.put("cc.strimzi.operator.strimzi-crd-api-version", "v1beta2");
        return overrides;
    }
}
