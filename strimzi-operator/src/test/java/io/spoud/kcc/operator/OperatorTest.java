package io.spoud.kcc.operator;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheManager;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserAuthorizationSimpleBuilder;
import io.strimzi.api.kafka.model.user.KafkaUserBuilder;
import io.strimzi.api.kafka.model.user.acl.AclOperation;
import io.strimzi.api.kafka.model.user.acl.AclResourcePatternType;
import io.strimzi.api.kafka.model.user.acl.AclRuleTopicResourceBuilder;
import io.strimzi.api.kafka.model.user.acl.AclRuleType;
import jakarta.inject.Inject;
import org.apache.avro.generic.GenericData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@WithKubernetesTestServer(port = 64444)
@TestProfile(DefaultTestProfile.class)
@QuarkusTestResource(KafkaCompanionResource.class)
@QuarkusTest
class OperatorTest {
    @Inject
    KubernetesClient client;

    @InjectKafkaCompanion
    KafkaCompanion kafkaCompanion;

    @Inject
    OperatorConfig config;

    @Inject
    KafkaTopicReconciler topicReconciler;

    @Inject
    KafkaUserReconciler userReconciler;

    @Inject
    CacheManager cacheManager;

    final String TOPIC_NAME = "test-topic";
    final String TOPIC_APP = "test-app";
    final String TOPIC_APP_KEY = "application";

    final String HIDDEN_TOPIC_NAME = "hidden-topic";

    final String GROUP_READER = "monitoring-stack";
    final String GROUP_WRITER = "audit-log";
    final String GROUP_DESCRIBER = "topic-monitor";
    final String GROUP_ADMIN = "admin";
    final String GROUP_DUMMY = "dummy";

    @BeforeEach
    public void setupKubernetesResources() {
        NamespaceBuilder builder = new NamespaceBuilder()
                .withNewMetadata().withName(config.namespace()).endMetadata();
        client.namespaces().resource(builder.build()).createOr(NonDeletingOperation::update);
        final String GROUP_KEY = config.contextAnnotationPrefix() + config.userIdContextAnnotation();

        // create some topics
        addKafkaTopic(getTopicInstance(TOPIC_NAME, TOPIC_APP));
        addKafkaTopic(getTopicInstance(HIDDEN_TOPIC_NAME, TOPIC_APP));
        // create a KafkaUser resource with permission to read from the topic
        addKafkaUser(getUserInstance("my-reader", Map.of(GROUP_KEY, GROUP_READER), AclResourcePatternType.LITERAL,
                TOPIC_NAME, List.of(AclOperation.READ, AclOperation.DESCRIBE, AclOperation.DESCRIBECONFIGS),
                List.of(AclOperation.WRITE, AclOperation.ALTER, AclOperation.DELETE)));
        // create a KafkaUser resource only with permission to write to the topic (no read permission)
        addKafkaUser(getUserInstance("my-writer", Map.of(GROUP_KEY, GROUP_WRITER), AclResourcePatternType.LITERAL,
                TOPIC_NAME, List.of(AclOperation.WRITE, AclOperation.DESCRIBE, AclOperation.DESCRIBECONFIGS),
                List.of(AclOperation.READ, AclOperation.ALTER, AclOperation.DELETE)));
        // create kafka user only with permissions to describe the topic (no read/write permission)
        addKafkaUser(getUserInstance("my-describer", Map.of(GROUP_KEY, GROUP_DESCRIBER), AclResourcePatternType.PREFIX,
                TOPIC_NAME, List.of(AclOperation.DESCRIBE, AclOperation.DESCRIBECONFIGS),
                List.of(AclOperation.DELETE)));
        // create kafka user with all permissions
        addKafkaUser(getUserInstance("my-admin", Map.of(GROUP_KEY, GROUP_ADMIN), AclResourcePatternType.PREFIX,
                "*", List.of(AclOperation.ALL),
                List.of()));
        // create kafka users with no permissions
        addKafkaUser(getUserInstance("dummy", Map.of(GROUP_KEY, GROUP_DUMMY), AclResourcePatternType.PREFIX,
                TOPIC_NAME, List.of(AclOperation.READ), // even though read is allowed, the deny ALL rule should take precedence
                List.of(AclOperation.ALL)));
        addKafkaUser(getUserInstance("dummy2", Map.of(GROUP_KEY, GROUP_DUMMY), AclResourcePatternType.PREFIX,
                TOPIC_NAME, List.of(AclOperation.READ), // even though read is allowed, the deny READ rule should take precedence
                List.of(AclOperation.READ)));

        // invalidate all caches
        cacheManager.getCacheNames().stream()
                .map(cacheManager::getCache)
                .flatMap(Optional::stream)
                .forEach(Cache::invalidateAll);
    }

    @Test
    @DisplayName("Test that a KafkaUser reconciliation triggers reconciliation of all topics")
    void testUserChangeTriggersTopicReconciliation() throws Exception {
        var username = "another-reader";
        var anotherGroup = UUID.randomUUID().toString();
        var annotations = Map.of(config.contextAnnotationPrefix() + config.userIdContextAnnotation(), anotherGroup);
        var user = getUserInstance(username, annotations, AclResourcePatternType.LITERAL,
                TOPIC_NAME, List.of(AclOperation.READ, AclOperation.DESCRIBE, AclOperation.DESCRIBECONFIGS),
                List.of(AclOperation.WRITE, AclOperation.ALTER, AclOperation.DELETE));
        addKafkaUser(user);

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000); // wait for the consumer to be ready
                userReconciler.reconcile(user, Mockito.mock(Context.class));
            } catch (Exception e) {
                fail(e);
            }
        });

        // make sure that both topics are reconciled
        var records = kafkaCompanion.consumeWithDeserializers(
                        StringDeserializer.class, KafkaAvroDeserializer.class
                ).fromTopics(config.contextDataTopic()).awaitNextRecords(2, Duration.ofSeconds(5))
                .getRecords();

        // make sure that the context of TOPIC_NAME now contains a new reader
        var record = records.stream()
                .filter(r -> TOPIC_NAME.equals(((GenericData.Record) r.value()).get("regex")))
                .findFirst()
                .orElseThrow();
        assertThatContextRecordMatchesTopic(record, getTopicInstance(TOPIC_NAME, TOPIC_APP),
                List.of(GROUP_ADMIN, GROUP_READER, anotherGroup), List.of(GROUP_ADMIN, GROUP_WRITER));
    }

    @Test
    @DisplayName("Test that a KafkaTopic reconciliation produces a context for each topic")
    void testReconcileAllTopics() {
        kafkaCompanion.setCommonClientConfig(Map.of("auto.offset.reset", "latest"));

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000); // wait for the consumer to be ready
                topicReconciler.reconcileAllResources();
            } catch (Exception e) {
                fail(e);
            }
        });

        // just make sure that the amount of records is correct, for a more detailed dive into the records see the other tests
        kafkaCompanion.consumeWithDeserializers(
                        StringDeserializer.class, KafkaAvroDeserializer.class
                ).fromTopics(config.contextDataTopic()).awaitNextRecords(2, Duration.ofSeconds(5))
                .getRecords();
    }

    @Test
    @DisplayName("Test that a KafkaTopic reconciliation produces the expected context for a single topic")
    void testReconcileSingleTopic() throws Exception {
        var context = Mockito.mock(Context.class);
        Mockito.when(context.getClient()).thenReturn(client);
        kafkaCompanion.setCommonClientConfig(Map.of("auto.offset.reset", "latest"));

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000); // wait for the consumer to be ready
                topicReconciler.reconcile(getTopicInstance(TOPIC_NAME, TOPIC_APP), context);
            } catch (Exception e) {
                fail(e);
            }
        });

        var record = kafkaCompanion.consumeWithDeserializers(
                        StringDeserializer.class, KafkaAvroDeserializer.class
                ).fromTopics(config.contextDataTopic()).awaitNextRecord(Duration.ofSeconds(5))
                .getFirstRecord();

        assertThatContextRecordMatchesTopic(record, getTopicInstance(TOPIC_NAME, TOPIC_APP),
                List.of(GROUP_ADMIN, GROUP_READER), List.of(GROUP_ADMIN, GROUP_WRITER));
    }

    void assertThatContextRecordMatchesTopic(ConsumerRecord record, KafkaTopic topic,
                                             List<String> expectedReaders, List<String> expectedWriters) {
        assertThat(record.value()).isInstanceOf(GenericData.Record.class);
        var value = (GenericData.Record) record.value();
        var ctx = (Map<String, String>) value.get("context");

        assertThat(TOPIC_NAME).matches(Pattern.compile((String) value.get("regex")));
        assertThat(value.get("entityType").toString()).isEqualTo("TOPIC");
        assertThat(ctx.get(config.writersContextKey()).split(",")).containsExactlyInAnyOrder(expectedWriters.toArray(new String[0]));
        assertThat(ctx.get(config.readersContextKey()).split(",")).containsExactlyInAnyOrder(expectedReaders.toArray(new String[0]));
        assertThat(ctx.get(TOPIC_APP_KEY)).isEqualTo(TOPIC_APP);
    }

    void addKafkaUser(KafkaUser u) {
        client.resources(KafkaUser.class)
                .inNamespace(config.namespace())
                .resource(u).createOr(NonDeletingOperation::update);
    }

    KafkaUser getUserInstance(String username, Map<String, String> annotations, AclResourcePatternType patternType,
                              String resourceName, List<AclOperation> allowedOperations, List<AclOperation> deniedOperations) {
        return new KafkaUserBuilder()
                .withNewMetadata()
                .withName(username)
                .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                .withAuthorization(
                        new KafkaUserAuthorizationSimpleBuilder()
                                .addNewAcl()
                                .withOperations(allowedOperations)
                                .withType(AclRuleType.ALLOW)
                                .withResource(
                                        new AclRuleTopicResourceBuilder()
                                                .withPatternType(patternType)
                                                .withName(resourceName)
                                                .build()
                                )
                                .endAcl()
                                .addNewAcl()
                                .withOperations(deniedOperations)
                                .withType(AclRuleType.DENY)
                                .withResource(
                                        new AclRuleTopicResourceBuilder()
                                                .withPatternType(patternType)
                                                .withName(resourceName)
                                                .build()
                                )
                                .endAcl()
                                .build()
                )
                .endSpec()
                .build();
    }

    void addKafkaTopic(KafkaTopic t) {
        client.resources(KafkaTopic.class)
                .inNamespace(config.namespace())
                .resource(t)
                .createOr(NonDeletingOperation::update);
    }

    KafkaTopic getTopicInstance(String topicName, String appName) {
        return new KafkaTopicBuilder()
                .withNewMetadata()
                .withName(topicName)
                .withAnnotations(Map.of(
                        config.contextAnnotationPrefix() + TOPIC_APP_KEY, appName
                ))
                .endMetadata()
                .withNewSpec()
                .withPartitions(1)
                .withReplicas(1)
                .endSpec()
                .build();
    }
}
