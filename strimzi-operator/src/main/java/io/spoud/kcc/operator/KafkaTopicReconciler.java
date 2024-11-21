package io.spoud.kcc.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkus.logging.Log;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KafkaTopicReconciler implements Reconciler<KafkaTopic> {

    private final KubernetesClient client;
    private final ContextExtractor contextExtractor;
    private final OperatorConfig config;

    public KafkaTopicReconciler(KubernetesClient client, ContextExtractor contextExtractor, OperatorConfig config) {
        this.client = client;
        this.config = config;
        this.contextExtractor = contextExtractor;
    }

    private void reconcileSingleResource(KafkaTopic t) {
        Log.infov("Reconciling Resource {0}", t.getMetadata().getName());
        var context = contextExtractor.getContextOfTopic(t);
        Log.infov("Resource context: {0}", context);
    }

    public void reconcileAllResources() {
        client.resources(KafkaTopic.class)
                .inNamespace(config.namespace())
                .list()
                .getItems()
                .forEach(this::reconcileSingleResource);
    }

    @Override
    public UpdateControl<KafkaTopic> reconcile(KafkaTopic t, Context<KafkaTopic> context) throws Exception {
        reconcileSingleResource(t);
        return UpdateControl.noUpdate();
    }
}
