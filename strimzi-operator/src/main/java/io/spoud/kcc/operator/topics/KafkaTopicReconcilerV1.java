package io.spoud.kcc.operator.topics;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkus.arc.properties.IfBuildProperty;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@IfBuildProperty(name = "cc.strimzi.crd-version", stringValue = "v1", enableIfMissing = true)
public class KafkaTopicReconcilerV1 implements Reconciler<KafkaTopic> {

    private final KafkaTopicReconciler kafkaTopicReconciler;

    public KafkaTopicReconcilerV1(KafkaTopicReconciler kafkaTopicReconciler) {
        this.kafkaTopicReconciler = kafkaTopicReconciler;
    }

    @Override
    public UpdateControl<KafkaTopic> reconcile(KafkaTopic t, Context<KafkaTopic> context) {
        kafkaTopicReconciler.reconcileSingleTopic(t);
        return UpdateControl.noUpdate();
    }
}
