package io.spoud.kcc.operator;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class ContextExtractor {
    private final KafkaUserService kafkaUserService;
    private final OperatorConfig config;

    public ContextExtractor(KafkaUserService kafkaUserService, OperatorConfig config) {
        this.kafkaUserService = kafkaUserService;
        this.config = config;
    }

    public Map<String, String> getContextOfResource(HasMetadata resource) {
        // iterate over all annotations of a resource (e.g. KafkaTopic) and extract the annotations that start with "spoud.io/kcc-context."
        // The key of the annotation is the context key and the value is the context value.
        var contextPrefix = config.contextAnnotationPrefix();
        var context = new HashMap<String, String>();
        resource.getMetadata()
                .getAnnotations()
                .entrySet()
                .stream()
                .filter(e -> e.getKey().startsWith(contextPrefix) && e.getKey().length() > contextPrefix.length())
                .forEach(e -> context.put(e.getKey().substring(contextPrefix.length()), e.getValue()));
        return context;
    }

    public Map<String, String> getContextOfTopic(KafkaTopic topic) {
        var topicName = topic.getMetadata().getName();
        var context = getContextOfResource(topic);

        // now add the list of readers/writers to the context
        context.put(config.readersContextKey(), kafkaUserService.getReadersOfTopic(topicName)
                .stream()
                .map(this::getContextOfResource)
                .map(ctx -> ctx.getOrDefault(config.userIdContextAnnotation(), config.userIdContextFallback()))
                .collect(Collectors.joining(",")));
        context.put(config.writersContextKey(), kafkaUserService.getWritersOfTopic(topicName)
                .stream()
                .map(this::getContextOfResource)
                .map(ctx -> ctx.getOrDefault(config.userIdContextAnnotation(), config.userIdContextFallback()))
                .collect(Collectors.joining(",")));

        return context;
    }
}
