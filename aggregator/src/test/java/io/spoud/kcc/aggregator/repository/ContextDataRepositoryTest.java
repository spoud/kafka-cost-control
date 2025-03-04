package io.spoud.kcc.aggregator.repository;

import io.smallrye.reactive.messaging.kafka.Record;
import io.spoud.kcc.aggregator.data.ContextDataEntity;
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
        List<ContextDataEntity> matchedContextData = contextDataRepository.testContext("test-topic-name");

        // then
        assertThat(matchedContextData).isEmpty();
    }

    @Test
    void testContext_onlySomeMatch() {
        // given
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        ContextData valid = new ContextData(Instant.now(), Instant.now().minusSeconds(100), null,
                EntityType.TOPIC, "^test.*", Map.of("key1", "value1"));
        ContextData validFromYesterday = new ContextData(yesterday, yesterday.minusSeconds(100), null,
                EntityType.TOPIC, "^test.*", Map.of("key1", "value1"));
        ContextData noLongerValid = new ContextData(yesterday, yesterday.minusSeconds(100), yesterday.plusSeconds(100),
                EntityType.TOPIC, "^test.*", Map.of("key1", "value1"));
        ContextData notYetValid = new ContextData(tomorrow, tomorrow.minusSeconds(100), null,
                EntityType.TOPIC, "^test.*", Map.of("key1", "value1"));
        ContextData regexDoesNotMatch = new ContextData(Instant.now(), Instant.now().minusSeconds(100), null,
                EntityType.TOPIC, "^testxxxxxx.*", Map.of("key1", "value1"));
        ContextData principalContext = new ContextData(Instant.now(), Instant.now().minusSeconds(100), null,
                EntityType.PRINCIPAL, "^test.*", Map.of("key1", "value1"));
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
        List<ContextDataEntity> matchedContextData = contextDataRepository.testContext("test-topic-name");

        // then
        assertThat(matchedContextData)
                .hasSize(3)
                .map(ContextDataEntity::id).containsExactlyInAnyOrder("valid", "validFromYesterday", "validPrincipal");
    }
}
