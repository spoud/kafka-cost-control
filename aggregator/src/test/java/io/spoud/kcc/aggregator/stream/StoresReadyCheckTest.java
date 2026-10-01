package io.spoud.kcc.aggregator.stream;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

class StoresReadyCheckTest {

    private static KafkaStreams streams(KafkaStreams.State state) {
        var streams = Mockito.mock(KafkaStreams.class);
        Mockito.when(streams.state()).thenReturn(state);
        return streams;
    }

    @Test
    void readyWhenRunningAndTheStoresAnswer() {
        var streams = streams(KafkaStreams.State.RUNNING);
        Mockito.when(streams.store(any(StoreQueryParameters.class))).thenReturn(Mockito.mock(ReadOnlyKeyValueStore.class));

        assertThat(new StoresReadyCheck(streams).call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
    }

    @Test
    void notReadyWhileStartingOrRebalancing() {
        for (var state : new KafkaStreams.State[]{KafkaStreams.State.CREATED, KafkaStreams.State.REBALANCING, KafkaStreams.State.ERROR}) {
            assertThat(new StoresReadyCheck(streams(state)).call().getStatus()).as(state.name())
                    .isEqualTo(HealthCheckResponse.Status.DOWN);
        }
    }

    @Test
    void notReadyWhileAStoreIsStillRestoring() {
        // what the demo hit after a restart: Streams running, the pricing store not queryable yet
        var streams = streams(KafkaStreams.State.RUNNING);
        Mockito.when(streams.store(any(StoreQueryParameters.class))).thenThrow(new InvalidStateStoreException("restoring"));

        var response = new StoresReadyCheck(streams).call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData().orElseThrow()).containsEntry("reason", "restoring");
    }
}
