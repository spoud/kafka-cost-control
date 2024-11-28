package io.spoud.kcc.operator;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.NotBlank;

@ConfigMapping(prefix = "cc.strimzi.operator")
public interface OperatorConfig {
    /**
     * Namespace where the operator watches for resources.
     */
    @WithDefault("kafka")
    @NotBlank
    String namespace();

    /**
     * Prefix for annotations that are used to store context data that will be used to generate topic contexts.
     */
    @WithDefault("spoud.io/kcc-context.")
    @NotBlank
    String contextAnnotationPrefix();

    /**
     * Annotation key by which KafkaUser resources are grouped together. For example, if team "Amazing Antelopes" owns two KafkaUsers
     * and both are annotated with "spoud.io/kcc-context.team=amazing-antelopes", and we want our topic contexts to include the name of
     * the team that is allowed to read/write the topic, we can use this annotation key to group the KafkaUsers together.
     * In this case, the context of the topic that these KafkaUsers are allowed to read/write would include a "readers" or "writers" key
     * with the value "amazing-antelopes" (and possibly other team names).
     */
    @WithName("user-group-key")
    @WithDefault("application")
    @NotBlank
    String userIdContextAnnotation();

    /**
     * Fallback value for the user group key. If a KafkaUser does not have the annotation specified by {@link #userIdContextAnnotation()},
     * this value will be used instead.
     */
    @WithName("user-group-fallback")
    @WithDefault("unknown")
    String userIdContextFallback();

    /**
     * Context key holding the comma-separated list of user groups that are allowed to write to the topic.
     */
    @WithName("writers-context-key")
    @WithDefault("writers")
    @NotBlank
    String writersContextKey();

    /**
     * Context key holding the comma-separated list of user groups that are allowed to read from the topic.
     */
    @WithName("readers-context-key")
    @WithDefault("readers")
    @NotBlank
    String readersContextKey();

    /**
     * Topic to which context data is published. Should be the same topic
     * as the one consumed by the Aggregator service (the Kafka Streams app)
     */
    @WithName("topics.context-data")
    @NotBlank
    String contextDataTopic();
}
