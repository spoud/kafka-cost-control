package io.spoud.kcc.operator.users;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.logging.Log;
import io.spoud.kcc.operator.ContextRecalculationScheduler;
import io.spoud.kcc.operator.users.v1beta2.KafkaUser;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@IfBuildProperty(name = "cc.strimzi.crd-version", stringValue = "v1beta2")
public class KafkaUserReconcilerV1Beta2 implements Reconciler<KafkaUser> {

    private final ContextRecalculationScheduler scheduler;

    public KafkaUserReconcilerV1Beta2(ContextRecalculationScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public UpdateControl<KafkaUser> reconcile(KafkaUser kafkaUser, Context<KafkaUser> context) {
        Log.debugv("Detected change in KafkaUser {0}, marking contexts for recalculation", kafkaUser.getMetadata().getName());
        scheduler.markChanged();
        return UpdateControl.noUpdate();
    }
}
