package io.spoud.kcc.operator.topics;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkus.arc.properties.IfBuildProperty;
import io.spoud.kcc.operator.topics.v1beta2.KafkaTopic;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@IfBuildProperty(name = "cc.strimzi.crd-version", stringValue = "v1beta2")
public class KafkaTopicReconcilerV1Beta2 implements Reconciler<KafkaTopic> {

    private final KafkaTopicReconciler kafkaTopicReconciler;

    public KafkaTopicReconcilerV1Beta2(KafkaTopicReconciler kafkaTopicReconciler) {
        this.kafkaTopicReconciler = kafkaTopicReconciler;
    }

    @Override
    public UpdateControl<KafkaTopic> reconcile(KafkaTopic t, Context<KafkaTopic> context) {
        kafkaTopicReconciler.reconcileSingleTopic(KafkaTopicReconciler.toKafkaTopic(t));
        return UpdateControl.noUpdate();
    }
}
