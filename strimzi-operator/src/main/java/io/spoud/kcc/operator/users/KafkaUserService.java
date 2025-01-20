package io.spoud.kcc.operator.users;

import io.quarkus.logging.Log;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserAuthorizationSimple;
import io.strimzi.api.kafka.model.user.acl.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class KafkaUserService {
    private final KafkaUserRepository kafkaUserRepository;

    public KafkaUserService(KafkaUserRepository kafkaUserRepository) {
        this.kafkaUserRepository = kafkaUserRepository;
    }

    /**
     * Get all Kafka users that have ACLs allowing them to read from the given topic.
     * Considers both literal and prefix ACLs.
     *
     * @param topicName the name of the topic
     * @return the Kafka users that have read access to the topic
     */
    public Collection<KafkaUser> getReadersOfTopic(String topicName) {
        return kafkaUserRepository.getAllUsers().stream()
                .filter(user -> canPerformOperationOnTopic(user, AclOperation.READ, topicName))
                .toList();
    }

    /**
     * Get all Kafka users that have ACLs allowing them to write to the given topic.
     * Considers both literal and prefix ACLs.
     * @param topicName the name of the topic
     * @return the Kafka users that have write access to the topic
     */
    public Collection<KafkaUser> getWritersOfTopic(String topicName) {
        return kafkaUserRepository.getAllUsers().stream()
                .filter(user -> canPerformOperationOnTopic(user, AclOperation.WRITE, topicName))
                .toList();
    }

    private boolean canPerformOperationOnTopic(KafkaUser user, AclOperation op, String topicName) {
        try {
            if (user.getSpec().getAuthorization() instanceof KafkaUserAuthorizationSimple auth) {
                var rules = getRulesForTopicName(auth.getAcls(), topicName);
                // deny rule takes precedence over allow rule, so we need to check that there is no deny rule
                return hasRuleForOperation(rules, op, AclRuleType.ALLOW) &&
                        !hasRuleForOperation(rules, op, AclRuleType.DENY);
            }
        } catch (Exception e) {
            Log.warnf(err, "Error while checking ACLs for user %s. Context generation will proceed, but the user will not be listed as a reader/writer.", user.getMetadata().getName());
        }
        return false; // only simple authorization is supported
    }

    private Collection<AclRule> getRulesForTopicName(Collection<AclRule> rules, String topicName) {
        return rules.stream()
                .filter(rule -> ruleAppliesToTopic(rule, topicName))
                .toList();
    }

    private boolean ruleAppliesToTopic(AclRule rule, String topicName) {
        if (rule.getResource().getType().equals(AclRuleTopicResource.TYPE_TOPIC) && rule.getResource() instanceof AclRuleTopicResource res) {
            if (res.getName().equals("*")) {
                return true; // wildcard rules apply to all topics
            }
            if (res.getPatternType() == AclResourcePatternType.PREFIX) {
                return topicName.startsWith(res.getName());
            }
            return res.getPatternType() == AclResourcePatternType.LITERAL && res.getName().equals(topicName);
        }
        return false;
    }

    private boolean hasRuleForOperation(Collection<AclRule> rules, AclOperation op, AclRuleType type) {
        return rules.stream().anyMatch(rule -> (getOperations(rule).contains(op)
                || getOperations(rule).contains(AclOperation.ALL))
                && rule.getType() == type);
    }

    private List<AclOperation> getOperations(AclRule rule) {
        if (rule.getOperations() == null) {
            return Optional.ofNullable(rule.getOperation())
                    .stream().toList();
        }
        return rule.getOperations();
    }
}
