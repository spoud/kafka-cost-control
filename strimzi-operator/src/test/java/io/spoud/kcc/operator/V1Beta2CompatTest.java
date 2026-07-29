package io.spoud.kcc.operator;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.user.KafkaUserBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code cc.strimzi.crd-version=v1beta2} actually resolves to the correct CRD group/version/plural
 * (kafka.strimzi.io/v1beta2, "kafkausers"/"kafkatopics") -- not just that the config switch exists.
 * A prior version of this fix used mirror classes named "KafkaUserV1beta2"/"KafkaTopicV1beta2", which fabric8
 * (lacking a @Kind/@Plural override annotation in this version) misread as resource kind
 * "KafkaUserV1beta2"/plural "kafkauserv1beta2s" -- wrong, and only caught by exercising this end-to-end.
 */
@WithKubernetesTestServer(port = 64445)
@TestProfile(V1Beta2TestProfile.class)
@QuarkusTestResource(KafkaCompanionResource.class)
@QuarkusTest
class V1Beta2CompatTest {

    @Inject
    KubernetesClient client;

    @Inject
    OperatorConfig config;

    @Test
    void kafkaUserV1beta2_resolvesToCorrectPluralAndIsListable() {
        client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(config.namespace()).endMetadata().build())
                .createOr(NonDeletingOperation::update);

        var user = new io.spoud.kcc.operator.users.v1beta2.KafkaUser();
        user.setMetadata(new KafkaUserBuilder().withNewMetadata().withName("v1beta2-user").endMetadata().build().getMetadata());
        client.resources(io.spoud.kcc.operator.users.v1beta2.KafkaUser.class)
                .inNamespace(config.namespace())
                .resource(user)
                .createOr(NonDeletingOperation::update);

        // If the class's simple name were mis-derived as resource kind/plural (e.g. "kafkauserv1beta2s"
        // instead of "kafkausers"), this list would come back empty (mock server 404s on the wrong path).
        assertThat(client.resources(io.spoud.kcc.operator.users.v1beta2.KafkaUser.class)
                .inNamespace(config.namespace()).list().getItems())
                .extracting(u -> u.getMetadata().getName())
                .containsExactly("v1beta2-user");
    }

    @Test
    void kafkaTopicV1beta2_resolvesToCorrectPluralAndIsListable() {
        client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(config.namespace()).endMetadata().build())
                .createOr(NonDeletingOperation::update);

        var topic = new io.spoud.kcc.operator.topics.v1beta2.KafkaTopic();
        topic.setMetadata(new KafkaTopicBuilder().withNewMetadata().withName("v1beta2-topic").endMetadata().build().getMetadata());
        client.resources(io.spoud.kcc.operator.topics.v1beta2.KafkaTopic.class)
                .inNamespace(config.namespace())
                .resource(topic)
                .createOr(NonDeletingOperation::update);

        assertThat(client.resources(io.spoud.kcc.operator.topics.v1beta2.KafkaTopic.class)
                .inNamespace(config.namespace()).list().getItems())
                .extracting(t -> t.getMetadata().getName())
                .containsExactly("v1beta2-topic");
    }
}
