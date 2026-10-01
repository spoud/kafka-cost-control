package io.spoud.kcc.aggregator.stream;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Ready only once Kafka Streams runs and the stores behind the API answer. The topics check alone
 * passes as soon as the topics exist, so right after a restart the pod took traffic while pricing
 * rules still failed with InvalidStateStoreException ("System error" in the UI).
 */
@Readiness
@ApplicationScoped
public class StoresReadyCheck implements HealthCheck {
    static final String NAME = "Kafka Streams stores readable";

    private final KafkaStreams kafkaStreams;

    public StoresReadyCheck(KafkaStreams kafkaStreams) {
        this.kafkaStreams = kafkaStreams;
    }

    @Override
    public HealthCheckResponse call() {
        var state = kafkaStreams.state();
        var response = HealthCheckResponse.named(NAME).withData("state", state.name());
        if (state != KafkaStreams.State.RUNNING) {
            return response.down().build();
        }
        try {
            for (String store : new String[]{MetricEnricher.PRICING_DATA_TABLE_NAME, MetricEnricher.CONTEXT_DATA_TABLE_NAME}) {
                kafkaStreams.store(StoreQueryParameters.fromNameAndType(store, QueryableStoreTypes.keyValueStore()))
                        .approximateNumEntries();
            }
            return response.up().build();
        } catch (InvalidStateStoreException e) {
            return response.withData("reason", e.getMessage()).down().build();
        }
    }
}
