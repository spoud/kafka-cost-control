package io.spoud.kcc.operator;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.NotBlank;

import java.util.Optional;

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

    /**
     * Context regex prefix. The regex is used to match the topic name to a context object.
     * This prefix will be prepended to each generated regex. Use this if, for example, you also use MirrorMaker2
     * which generates replicated topics with a prefix
     * <p>
     * For example, if for each topic with <topicname> MirrorMaker2 generates a replica called "eu-west-1.<topicname>",
     * you could set this prefix to "(eu-west-1.)?" to match both, the prefixed and non-prefixed topics with the same context object.
     */
    @WithName("context-regex-prefix")
    Optional<String> contextRegexPrefix();

    /**
     * Context regex suffix. The regex is used to match the topic name to a context object.
     * This suffix will be appended to each generated regex. Use this if, for example, you also use MirrorMaker2
     * which generates replicated topics with a suffix
     * <p>
     * For example, if for each topic with <topicname> MirrorMaker2 generates a replica called "<topicname>.eu-west-1",
     * you could set this suffix to "(eu-west-1)?" to match both, the suffixed and non-suffixed topics with the same context object.
     */
    @WithName("context-regex-suffix")
    Optional<String> contextRegexSuffix();
}
