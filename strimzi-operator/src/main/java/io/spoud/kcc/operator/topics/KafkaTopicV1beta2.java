package io.spoud.kcc.operator.topics;

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
 */
@Group("kafka.strimzi.io")
@Version("v1beta2")
public class KafkaTopicV1beta2 extends CustomResource<KafkaTopicSpec, KafkaTopicStatus> implements Namespaced {
}
