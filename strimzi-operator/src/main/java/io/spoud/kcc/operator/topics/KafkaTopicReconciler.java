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
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class KafkaTopicReconciler {
    public static final String CONTEXT_CHANNEL = "context-data-out";

    private final KubernetesClient client;
    private final ContextExtractor contextExtractor;
    private final OperatorConfig config;
    private final Emitter<Record<String, ContextData>> contextEmitter;
    private final ContextRepository contextRepository;

    public KafkaTopicReconciler(KubernetesClient client,
                                ContextExtractor contextExtractor,
                                OperatorConfig config,
                                @Channel(CONTEXT_CHANNEL) Emitter<Record<String, ContextData>> contextEmitter,
                                ContextRepository contextRepository) {
        this.client = client;
        this.config = config;
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
        if ("v1beta2".equals(config.strimziCrdApiVersion())) {
            return client.resources(KafkaTopicV1beta2.class).inNamespace(config.namespace()).list().getItems()
                    .stream().map(KafkaTopicReconciler::toKafkaTopic).toList();
        }
        return client.resources(KafkaTopic.class).inNamespace(config.namespace()).list().getItems();
    }

    private static KafkaTopic toKafkaTopic(KafkaTopicV1beta2 legacy) {
        KafkaTopic topic = new KafkaTopic();
        topic.setMetadata(legacy.getMetadata());
        topic.setSpec(legacy.getSpec());
        topic.setStatus(legacy.getStatus());
        return topic;
    }
}
