package io.spoud.kcc.operator;

import io.smallrye.reactive.messaging.kafka.Record;
import io.spoud.kcc.data.ContextData;
import io.spoud.kcc.data.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRepositoryTest {

    @Test
    @DisplayName("Test retrieving added context")
    void getContext() {
        var record = Record.of("mytopic", ContextData
                .newBuilder()
                .setContext(Map.of("region", "europe"))
                .setRegex("mytopic")
                .setEntityType(EntityType.TOPIC)
                .setValidFrom(Instant.EPOCH)
                .setValidUntil(null)
                .setCreationTime(Instant.now())
                .build()
        );

        var contextRepository = new ContextRepository();

        assertThat(contextRepository.getContext("mytopic")).isNull();

        contextRepository.updateContext(record);

        var context = contextRepository.getContext("mytopic");
        assertThat(context).isNotNull();
        assertThat(context.getContext()).containsExactlyEntriesOf(Map.of("region", "europe"));

        // now test that the context is overwritten
        var record2 = Record.of("mytopic", ContextData
                .newBuilder()
                .setContext(Map.of("region", "asia"))
                .setRegex("mytopic")
                .setEntityType(EntityType.TOPIC)
                .setValidFrom(Instant.EPOCH)
                .setValidUntil(null)
                .setCreationTime(Instant.now())
                .build()
        );

        contextRepository.updateContext(record2);
        context = contextRepository.getContext("mytopic");
        assertThat(context).isNotNull();
        assertThat(context.getContext()).containsExactlyEntriesOf(Map.of("region", "asia"));
    }

    @Test
    @DisplayName("Test checking if identical context already exists under the given key")
    void containsContext() {
        var record = Record.of("mytopic", ContextData
                .newBuilder()
                .setContext(Map.of("region", "europe"))
                .setRegex("mytopic")
                .setEntityType(EntityType.TOPIC)
                .setValidFrom(Instant.EPOCH)
                .setValidUntil(null)
                .setCreationTime(Instant.now())
                .build()
        );

        var contextRepository = new ContextRepository();
        assertThat(contextRepository.containsContext("mytopic", record.value())).isFalse();
        contextRepository.updateContext(record);
        assertThat(contextRepository.containsContext("mytopic", record.value())).isTrue();

        // now test that querying by the same key but different context returns false
        var differentContext = ContextData
                .newBuilder()
                .setContext(Map.of("region", "asia"))
                .setRegex("mytopic")
                .setEntityType(EntityType.TOPIC)
                .setValidFrom(Instant.EPOCH)
                .setValidUntil(null)
                .setCreationTime(Instant.now())
                .build();
        assertThat(contextRepository.containsContext("mytopic", differentContext)).isFalse();
    }
}
