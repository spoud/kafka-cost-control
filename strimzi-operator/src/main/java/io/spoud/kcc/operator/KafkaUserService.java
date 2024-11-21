package io.spoud.kcc.operator;

import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserAuthorizationSimple;
import io.strimzi.api.kafka.model.user.acl.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;

@ApplicationScoped
public class KafkaUserService {
    private final KafkaUserRepository kafkaUserRepository;

    public KafkaUserService(KafkaUserRepository kafkaUserRepository) {
        this.kafkaUserRepository = kafkaUserRepository;
    }

    public Collection<KafkaUser> getReadersOfTopic(String topicName) {
        return kafkaUserRepository.getAllUsers().stream()
                .filter(user -> canPerformOperationOnTopic(user, AclOperation.READ, topicName))
                .toList();
    }

    public Collection<KafkaUser> getWritersOfTopic(String topicName) {
        return kafkaUserRepository.getAllUsers().stream()
                .filter(user -> canPerformOperationOnTopic(user, AclOperation.WRITE, topicName))
                .toList();
    }

    private boolean canPerformOperationOnTopic(KafkaUser user, AclOperation op, String topicName) {
        if (user.getSpec().getAuthorization() instanceof KafkaUserAuthorizationSimple auth) {
            var rules = getRulesForTopicName(auth.getAcls(), topicName);
            // deny rule takes precedence over allow rule, so we need to check that there is no deny rule
            return hasRuleForOperation(rules, op, AclRuleType.ALLOW) &&
                    !hasRuleForOperation(rules, op, AclRuleType.DENY);
        }
        return false; // only simple authorization is supported
    }

    private Collection<AclRule> getRulesForTopicName(Collection<AclRule> rules, String topicName) {
        return rules.stream()
                .filter(rule -> ruleAppliesTopic(rule, topicName))
                .toList();
    }

    private boolean ruleAppliesTopic(AclRule rule, String topicName) {
        if (rule.getResource().getType().equals(AclRuleTopicResource.TYPE_TOPIC) && rule.getResource() instanceof AclRuleTopicResource res) {
            if (res.getPatternType() == AclResourcePatternType.PREFIX && topicName.startsWith(res.getName())) {
                return true;
            }
            return res.getPatternType() == AclResourcePatternType.LITERAL && res.getName().equals(topicName);
        }
        return false;
    }

    private boolean hasRuleForOperation(Collection<AclRule> rules, AclOperation op, AclRuleType type) {
        return rules.stream().anyMatch(rule -> (rule.getOperations().contains(op) || rule.getOperations().contains(AclOperation.ALL))
                && rule.getType() == type);
    }
}
