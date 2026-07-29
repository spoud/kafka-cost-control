package io.spoud.kcc.operator;

/**
 * Which CRD API version this build targets for KafkaUser/KafkaTopic. Exactly one implementation is ever
 * registered, gated by the {@code cc.strimzi.crd-version} build-time property (see {@link CrdVersionV1}/
 * {@link CrdVersionV1Beta2}) -- the same mechanism that selects which JOSDK reconciler variant is active.
 *
 * <p>Deliberately not a plain {@code @ConfigProperty}: that resolves at runtime from
 * {@code application.yaml}'s default, which does not see the {@code -D} flag passed to the Maven build
 * that produced this artifact -- only {@code @IfBuildProperty} (a true build-time construct) does. Using
 * the same annotation for both the reconciler selection and this value keeps them consistent by
 * construction.
 */
public interface CrdVersion {
    boolean isV1Beta2();
}
