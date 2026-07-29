package io.spoud.kcc.operator.topics;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.kafka.Record;
import io.spoud.kcc.data.ContextData;
import io.spoud.kcc.data.EntityType;
import io.spoud.kcc.operator.ContextExtractor;
import io.spoud.kcc.operator.ContextRepository;
import io.spoud.kcc.operator.OperatorConfig;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.time.Instant;
import java.util.List;

/**
 * Holds the actual reconciliation logic, always present regardless of which CRD version this build
 * targets. Used by {@link io.spoud.kcc.operator.ContextRecalculationScheduler} (the poll-driven safety
 * net) and by whichever of {@link KafkaTopicReconcilerV1}/{@link KafkaTopicReconcilerV1Beta2} is the
 * active JOSDK reconciler for this build (see {@code cc.strimzi.crd-version}) -- this class itself is not
 * a JOSDK reconciler.
 */
@ApplicationScoped
public class KafkaTopicReconciler {
    public static final String CONTEXT_CHANNEL = "context-data-out";

    private final KubernetesClient client;
    private final ContextExtractor contextExtractor;
    private final OperatorConfig config;
    private final String crdVersion;
    private final Emitter<Record<String, ContextData>> contextEmitter;
    private final ContextRepository contextRepository;

    public KafkaTopicReconciler(KubernetesClient client,
                                ContextExtractor contextExtractor,
                                OperatorConfig config,
                                @ConfigProperty(name = "cc.strimzi.crd-version") String crdVersion,
                                @Channel(CONTEXT_CHANNEL) Emitter<Record<String, ContextData>> contextEmitter,
                                ContextRepository contextRepository) {
        this.client = client;
        this.config = config;
        this.crdVersion = crdVersion;
        this.contextExtractor = contextExtractor;
        this.contextEmitter = contextEmitter;
        this.contextRepository = contextRepository;
    }

    public void reconcileSingleTopic(KafkaTopic t) {
        Log.debugv("Reconciling KafkaTopic {0}", t.getMetadata().getName());
        var context = contextExtractor.getContextOfTopic(t);
        var suffix = config.contextRegexSuffix().orElse("");
        var prefix = config.contextRegexPrefix().orElse("");
        var key = t.getMetadata().getName();
        var value = ContextData.newBuilder()
                .setCreationTime(Instant.now())
                .setContext(context)
                .setEntityType(EntityType.TOPIC)
                .setRegex(String.format("%s%s%s", prefix, t.getMetadata().getName().replaceAll("[.]", "[.]"), suffix))
                .build();
        if (!contextRepository.containsContext(key, value)) { // only publish if the context has changed
            contextEmitter.send(Record.of(key, value)).whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    Log.errorv("Unable to send context to kafka for key {0}: {1}", t.getMetadata().getName(), context, throwable);
                } else {
                    Log.debugv("Context data updated on kafka for key {0}: {1}", t.getMetadata().getName(), context);
                }
            });
        } else {
            Log.infov("Context data for key {0} has not changed, skipping update", t.getMetadata().getName());
        }
    }

    /**
     * Recalculate and publish contexts for all Kafka topics.
     */
    public void reconcileAllTopics() {
        var topicList = fetchTopics();
        Log.infov("Reconciling contexts for all {0} KafkaTopics", topicList.size());
        topicList.forEach(this::reconcileSingleTopic);
    }

    private List<KafkaTopic> fetchTopics() {
        if ("v1beta2".equals(crdVersion)) {
            return client.resources(io.spoud.kcc.operator.topics.v1beta2.KafkaTopic.class).inNamespace(config.namespace()).list().getItems()
                    .stream().map(KafkaTopicReconciler::toKafkaTopic).toList();
        }
        return client.resources(KafkaTopic.class).inNamespace(config.namespace()).list().getItems();
    }

    static KafkaTopic toKafkaTopic(io.spoud.kcc.operator.topics.v1beta2.KafkaTopic legacy) {
        KafkaTopic topic = new KafkaTopic();
        topic.setMetadata(legacy.getMetadata());
        topic.setSpec(legacy.getSpec());
        topic.setStatus(legacy.getStatus());
        return topic;
    }
}
