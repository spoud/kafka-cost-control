package io.spoud.kcc.aggregator.repository;

import io.smallrye.reactive.messaging.kafka.Record;
import io.spoud.kcc.aggregator.data.ContextTestResponse;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.aggregator.stream.TelegrafDataWrapper;
import io.spoud.kcc.data.ContextData;
import io.spoud.kcc.data.EntityType;
import org.apache.kafka.streams.KafkaStreams;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class ContextDataStreamRepositoryTest {

    @Mock
    Emitter<Record<String, ContextData>> contextEmitter;
    @Mock
    KafkaStreams kafkaStreams;

    @Spy
    @InjectMocks
    ContextDataStreamRepository contextDataStreamRepository;

    @Test
    void testContext_noCachedContextData_resultConsistsOfEmptyArrays() {
        // given
        doReturn(Collections.emptyList()).when(contextDataStreamRepository).getCachedContextData();

        // when
        List<ContextTestResponse> matchedContextData = contextDataStreamRepository.testContext("test-topic-name");

        // then
        assertThat(matchedContextData)
                .map(ContextTestResponse::context)
                .allSatisfy(context -> assertThat(context).isEmpty());
    }

    @Test
    void testContext_onlySomeMatch() {
        // given
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        ContextData valid = new ContextData(Instant.now(), Instant.now().minusSeconds(100), null,
                EntityType.TOPIC, "^test.*", Map.of("key1", "value"));
        ContextData validFromYesterday = new ContextData(yesterday, yesterday.minusSeconds(100), null,
                EntityType.TOPIC, "^test.*", Map.of("key2", "value"));
        ContextData noLongerValid = new ContextData(yesterday, yesterday.minusSeconds(100), yesterday.plusSeconds(100),
                EntityType.TOPIC, "^test.*", Map.of("key3", "value"));
        ContextData notYetValid = new ContextData(tomorrow, tomorrow.minusSeconds(100), null,
                EntityType.TOPIC, "^test.*", Map.of("key4", "value"));
        ContextData regexDoesNotMatch = new ContextData(Instant.now(), Instant.now().minusSeconds(100), null,
                EntityType.TOPIC, "^testxxxxxx.*", Map.of("key5", "value"));
        ContextData principalContext = new ContextData(Instant.now(), Instant.now().minusSeconds(100), null,
                EntityType.PRINCIPAL, "^test.*", Map.of("key6", "value"));
        doReturn(
                List.of(
                        new ContextDataStreamRepository.CachedContextData("valid", valid),
                        new ContextDataStreamRepository.CachedContextData("validFromYesterday", validFromYesterday),
                        new ContextDataStreamRepository.CachedContextData(UUID.randomUUID().toString(), noLongerValid),
                        new ContextDataStreamRepository.CachedContextData(UUID.randomUUID().toString(), notYetValid),
                        new ContextDataStreamRepository.CachedContextData(UUID.randomUUID().toString(), regexDoesNotMatch),
                        new ContextDataStreamRepository.CachedContextData("validPrincipal", principalContext)
                )
        ).when(contextDataStreamRepository).getCachedContextData();

        // when
        List<ContextTestResponse> matchedContextData = contextDataStreamRepository.testContext("test-topic-name");

        // then
        assertThat(matchedContextData)
                .filteredOn(x -> x.entityType() == EntityType.TOPIC)
                .allSatisfy(response -> assertThat(response.context()).containsOnlyKeys("key1", "key2"))
                .filteredOn(x -> x.entityType() == EntityType.PRINCIPAL)
                .allSatisfy(response -> assertThat(response.context()).containsOnlyKeys("key6"));
    }

    @Test
    void testContext_newerContext_overridesOlderOne() {
        // given
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        ContextData valid = new ContextData(Instant.now(), Instant.now().minusSeconds(100), null,
                EntityType.TOPIC, "^test.*", Map.of("key1", "value1"));
        ContextData validFromYesterday = new ContextData(Instant.now().plusSeconds(2), yesterday.minusSeconds(100), null,
                EntityType.TOPIC, "^test.*", Map.of("key1", "newValue"));

        doReturn(
                List.of(
                        new ContextDataStreamRepository.CachedContextData("valid", valid),
                        new ContextDataStreamRepository.CachedContextData("validFromYesterday", validFromYesterday)
                )
        ).when(contextDataStreamRepository).getCachedContextData();

        // when
        List<ContextTestResponse> matchedContextData = contextDataStreamRepository.testContext("test-topic-name");

        // then
        assertThat(matchedContextData)
                .filteredOn(x -> x.entityType() == EntityType.TOPIC)
                .first().satisfies(x -> assertThat(x.context()).contains(entry("key1", "newValue")));
    }

    @Test
    void testContext_regexWithContextPlaceHolders_getReplaced() {
        // given
        ContextData valid = new ContextData(Instant.now(), Instant.now().minusSeconds(100), null,
                EntityType.TOPIC, "(.*)-(.*)-(.*)",
                Map.of("first-capturing-group", "$1",
                        "second-capturing-group", "$2",
                        "third-capturing-group", "$3",
                        "all-together", "$1;$2;$3"));

        doReturn(
                List.of(
                        new ContextDataStreamRepository.CachedContextData("valid", valid)
                )
        ).when(contextDataStreamRepository).getCachedContextData();

        // when
        List<ContextTestResponse> matchedContextData = contextDataStreamRepository.testContext("test-topic-name");

        // then
        assertThat(matchedContextData)
                .filteredOn(x -> x.entityType() == EntityType.TOPIC)
                .first().satisfies(x -> assertThat(x.context()).contains(
                        entry("first-capturing-group", "test"),
                        entry("second-capturing-group", "topic"),
                        entry("third-capturing-group", "name"),
                        entry("all-together", "test;topic;name"))
                );
    }

    private void rules(ContextData... rules) {
        doReturn(java.util.Arrays.stream(rules)
                .map(r -> new ContextDataStreamRepository.CachedContextData(UUID.randomUUID().toString(), r))
                .toList()).when(contextDataStreamRepository).getCachedContextData();
    }

    private static ContextData rule(EntityType type, String regex, Map<String, String> context) {
        return new ContextData(Instant.now().minusSeconds(60), null, null, type, regex, context);
    }

    private Map<String, String> contextOf(Map<String, String> tags) {
        var data = new RawTelegrafData(Instant.now(), "confluent_kafka_server_request_bytes", Map.of("gauge", 1.0), tags);
        return contextDataStreamRepository.enrichWithContext(new TelegrafDataWrapper(data)).orElseThrow().context();
    }

    @Test
    void principalRulesMatchTheDisplayNameWithCaptureGroups() {
        // one naming-convention rule covers every account, including ones created later
        rules(rule(EntityType.PRINCIPAL, "^kcc-demo-([^.]+)\\.app\\.(.+)$", Map.of("tenant", "$1", "application", "$2")));

        var context = contextOf(Map.of("principal_id", "sa-new123",
                "principal_name", "kcc-demo-northstar-logistics.app.payment-service"));

        assertThat(context).containsOnly(entry("tenant", "northstar-logistics"), entry("application", "payment-service"));
    }

    @Test
    void idRulesKeepWorkingAndMergeWithNameRules() {
        rules(rule(EntityType.PRINCIPAL, "^(sa-new123)$", Map.of("service_account", "$1")),
                rule(EntityType.PRINCIPAL, "^kcc-demo-([^.]+)\\..*$", Map.of("tenant", "$1")));

        var context = contextOf(Map.of("principal_id", "sa-new123", "principal_name", "kcc-demo-acme.app.x"));

        assertThat(context).containsOnly(entry("service_account", "sa-new123"), entry("tenant", "acme"));
    }

    @Test
    void withoutADisplayNameOnlyTheIdIsMatched() {
        rules(rule(EntityType.PRINCIPAL, "^kcc-demo-.*$", Map.of("tenant", "x")));

        assertThat(contextOf(Map.of("principal_id", "sa-new123"))).isEmpty();
        assertThat(contextOf(Map.of("principal_id", "sa-new123", "principal_name", " "))).isEmpty();
    }

    @Test
    void topicsAreNotMatchedAgainstAPrincipalName() {
        rules(rule(EntityType.TOPIC, "^kcc-demo-.*$", Map.of("tenant", "x")));

        assertThat(contextOf(Map.of("topic", "orders", "principal_name", "kcc-demo-acme"))).isEmpty();
    }
}
