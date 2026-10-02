package io.spoud.kcc.aggregator.olap;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.spoud.kcc.aggregator.data.UnassignedEntity;
import io.spoud.kcc.data.EntityType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnassignedEntitiesGaugesTest {

    @Test
    void countsUnassignedTopicsAndPrincipalsAndTheirCost() {
        var registry = new SimpleMeterRegistry();
        var gauges = new UnassignedEntitiesGauges(Mockito.mock(ContextDataOlapRepository.class), registry);

        gauges.update(List.of(
                new UnassignedEntity(EntityType.PRINCIPAL, "sa-new", List.of("request_bytes"), 0.25, Instant.now()),
                new UnassignedEntity(EntityType.PRINCIPAL, "u-human", List.of("response_bytes"), 0.5, Instant.now()),
                new UnassignedEntity(EntityType.TOPIC, "odd-topic", List.of("retained_bytes"), 0.0, Instant.now())));

        assertThat(registry.get("kcc.unassigned.entities").tag("entity_type", "PRINCIPAL").gauge().value()).isEqualTo(2);
        assertThat(registry.get("kcc.unassigned.entities").tag("entity_type", "TOPIC").gauge().value()).isEqualTo(1);
        assertThat(registry.get("kcc.unassigned.cost").gauge().value()).isEqualTo(0.75);

        gauges.update(List.of());
        assertThat(registry.get("kcc.unassigned.cost").gauge().value()).isZero();
    }
}
