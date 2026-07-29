package io.spoud.kcc.operator;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@IfBuildProperty(name = "cc.strimzi.crd-version", stringValue = "v1beta2")
public class CrdVersionV1Beta2 implements CrdVersion {
    @Override
    public boolean isV1Beta2() {
        return true;
    }
}
