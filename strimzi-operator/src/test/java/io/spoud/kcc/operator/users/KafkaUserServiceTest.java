package io.spoud.kcc.operator.users;

import io.strimzi.api.kafka.model.user.acl.AclOperation;
import io.strimzi.api.kafka.model.user.acl.AclRule;
import io.strimzi.api.kafka.model.user.acl.AclRuleBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaUserServiceTest {

    private final KafkaUserService service = new KafkaUserService(null);

    @Test
    void getOperations_returnsPluralOperationsWhenPresent() {
        AclRule rule = new AclRuleBuilder().withOperations(List.of(AclOperation.READ, AclOperation.WRITE)).build();

        assertThat(service.getOperations(rule)).containsExactly(AclOperation.READ, AclOperation.WRITE);
    }

    @Test
    void getOperations_fallsBackToLegacySingularOperationField() {
        // Simulates the deprecated pre-v1 CRD schema, which used a singular "operation" field instead of
        // the plural "operations". AclRule captures unknown JSON fields like this in additionalProperties
        // (via @JsonAnySetter) rather than discarding them, so a real cluster serving that schema version
        // would deserialize this the same way.
        AclRule rule = new AclRuleBuilder().build();
        rule.setAdditionalProperty("operation", "Read");

        assertThat(service.getOperations(rule)).containsExactly(AclOperation.READ);
    }

    @Test
    void getOperations_legacyFieldUsesJsonValueNotEnumName() {
        // AclOperation's JSON representation ("All") differs from its Java enum constant name (ALL),
        // via @JsonValue/@JsonCreator forValue(). The fallback must go through forValue(), not valueOf().
        AclRule rule = new AclRuleBuilder().build();
        rule.setAdditionalProperty("operation", "All");

        assertThat(service.getOperations(rule)).containsExactly(AclOperation.ALL);
    }

    @Test
    void getOperations_returnsEmptyWhenNeitherFieldIsPresent() {
        AclRule rule = new AclRuleBuilder().build();

        assertThat(service.getOperations(rule)).isEmpty();
    }

    @Test
    void getOperations_returnsEmptyForUnrecognizedLegacyValue() {
        AclRule rule = new AclRuleBuilder().build();
        rule.setAdditionalProperty("operation", "NotARealOperation");

        assertThat(service.getOperations(rule)).isEmpty();
    }
}
