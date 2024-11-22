package io.spoud.kcc.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.kafka.Record;
import io.spoud.kcc.data.ContextData;
import io.spoud.kcc.data.EntityType;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.time.Instant;

@ApplicationScoped
public class KafkaTopicReconciler implements Reconciler<KafkaTopic> {
    public static final String CONTEXT_CHANNEL = "context-data-out";

    private final KubernetesClient client;
    private final ContextExtractor contextExtractor;
    private final OperatorConfig config;
    private final Emitter<Record<String, ContextData>> contextEmitter;

    public KafkaTopicReconciler(KubernetesClient client,
                                ContextExtractor contextExtractor,
                                OperatorConfig config,
                                @Channel(CONTEXT_CHANNEL) Emitter<Record<String, ContextData>> contextEmitter) {
        this.client = client;
        this.config = config;
        this.contextExtractor = contextExtractor;
        this.contextEmitter = contextEmitter;
    }

    private void reconcileSingleResource(KafkaTopic t) {
        var context = contextExtractor.getContextOfTopic(t);
        Log.debugv("Resource context: {0}", context);
        // publish the context to a Kafka topic
        var record = Record.of(t.getMetadata().getName(), ContextData.newBuilder()
                .setCreationTime(Instant.now())
                .setContext(context)
                .setEntityType(EntityType.TOPIC)
                .setRegex(t.getMetadata().getName().replaceAll("[.]", "[.]"))
                .build()
        );
        contextEmitter.send(record).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                Log.errorv("Unable to send context to kafka for key {0}: {1}", t.getMetadata().getName(), context, throwable);
            } else {
                Log.debugv("Context data updated on kafka for key {0}: {1}", t.getMetadata().getName(), context);
            }
        });
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
