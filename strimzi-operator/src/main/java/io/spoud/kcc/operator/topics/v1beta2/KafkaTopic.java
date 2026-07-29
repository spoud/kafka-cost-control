package io.spoud.kcc.operator.topics.v1beta2;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.strimzi.api.kafka.model.topic.KafkaTopicSpec;
import io.strimzi.api.kafka.model.topic.KafkaTopicStatus;

/**
 * Mirrors {@link io.strimzi.api.kafka.model.topic.KafkaTopic}, targeting the "v1beta2" CRD version instead of "v1".
 * Used when the target cluster's Strimzi Cluster Operator only serves "v1beta2" for KafkaTopic
 * (io.strimzi:api 1.x only declares "v1"). Spec/status types are identical across versions.
 *
 * <p>This class must be named/packaged exactly like this: fabric8 derives the resource kind and plural
 * (used to build the REST path) from this class's simple name via reflection -- there is no
 * {@code @Kind}/{@code @Plural} annotation available in this fabric8 version to override it. Naming it
 * "KafkaTopic" (in this dedicated sub-package, to avoid clashing with the real
 * {@code io.strimzi.api.kafka.model.topic.KafkaTopic} import) makes that derivation resolve correctly to
 * "KafkaTopic"/"kafkatopics", matching the real CRD.
 */
@Group("kafka.strimzi.io")
@Version("v1beta2")
public class KafkaTopic extends CustomResource<KafkaTopicSpec, KafkaTopicStatus> implements Namespaced {
}
