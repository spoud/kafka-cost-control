package io.spoud.kcc.aggregator.repository;

import io.smallrye.reactive.messaging.kafka.Record;
import io.spoud.kcc.aggregator.data.ContextTestResponse;
import io.spoud.kcc.aggregator.stream.CachedContextDataManager;
import io.spoud.kcc.data.ContextData;
import io.spoud.kcc.data.EntityType;
import org.apache.kafka.streams.KafkaStreams;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextDataRepositoryTest {

    @Mock
    Emitter<Record<String, ContextData>> contextEmitter;
    @Mock
    KafkaStreams kafkaStreams;
    @Mock
    CachedContextDataManager cachedContextDataManager;

    @InjectMocks
    ContextDataRepository contextDataRepository;

    @Test
    void testContext_noCachedContextData_resultConsistsOfEmptyArrays() {
        // given
        when(cachedContextDataManager.getCachedContextData()).thenReturn(Collections.emptyList());

        // when
        List<ContextTestResponse> matchedContextData = contextDataRepository.testContext("test-topic-name");

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
        when(cachedContextDataManager.getCachedContextData()).thenReturn(
                List.of(
                        new CachedContextDataManager.CachedContextData("valid", valid),
                        new CachedContextDataManager.CachedContextData("validFromYesterday", validFromYesterday),
                        new CachedContextDataManager.CachedContextData(UUID.randomUUID().toString(), noLongerValid),
                        new CachedContextDataManager.CachedContextData(UUID.randomUUID().toString(), notYetValid),
                        new CachedContextDataManager.CachedContextData(UUID.randomUUID().toString(), regexDoesNotMatch),
                        new CachedContextDataManager.CachedContextData("validPrincipal", principalContext)
                )
        );

        // when
        List<ContextTestResponse> matchedContextData = contextDataRepository.testContext("test-topic-name");

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

        when(cachedContextDataManager.getCachedContextData()).thenReturn(
                List.of(
                        new CachedContextDataManager.CachedContextData("valid", valid),
                        new CachedContextDataManager.CachedContextData("validFromYesterday", validFromYesterday)
                )
        );

        // when
        List<ContextTestResponse> matchedContextData = contextDataRepository.testContext("test-topic-name");

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

        when(cachedContextDataManager.getCachedContextData()).thenReturn(
                List.of(
                        new CachedContextDataManager.CachedContextData("valid", valid)
                )
        );

        // when
        List<ContextTestResponse> matchedContextData = contextDataRepository.testContext("test-topic-name");

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
}
