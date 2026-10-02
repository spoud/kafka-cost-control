package io.spoud.kcc.aggregator.olap;

import com.google.common.util.concurrent.AtomicDouble;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.aggregator.data.UnassignedEntity;
import io.spoud.kcc.data.EntityType;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus gauges for topics and principals without a tenant over the last 24 hours, so a new
 * service account or a topic outside the naming convention can raise an alert instead of silently
 * landing in "<other>".
 */
@ApplicationScoped
public class UnassignedEntitiesGauges {
    static final Duration LOOKBACK = Duration.ofHours(24);
    static final String CONTEXT_KEY = "tenant";

    private final ContextDataOlapRepository repository;
    private final Map<EntityType, AtomicLong> counts = Map.of(
            EntityType.TOPIC, new AtomicLong(), EntityType.PRINCIPAL, new AtomicLong());
    private final AtomicDouble cost = new AtomicDouble();

    public UnassignedEntitiesGauges(ContextDataOlapRepository repository, MeterRegistry registry) {
        this.repository = repository;
        counts.forEach((type, count) -> Gauge.builder("kcc.unassigned.entities", count, AtomicLong::get)
                .description("Topics or principals without a " + CONTEXT_KEY + " in the last 24 hours")
                .tag("entity_type", type.name())
                .register(registry));
        Gauge.builder("kcc.unassigned.cost", cost, AtomicDouble::get)
                .description("Pricing-rule cost of topics and principals without a " + CONTEXT_KEY + " in the last 24 hours")
                .register(registry);
    }

    @Scheduled(every = "10m", delayed = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        update(repository.unassignedEntities(Instant.now().minus(LOOKBACK), null, CONTEXT_KEY));
    }

    void update(List<UnassignedEntity> unassigned) {
        counts.forEach((type, count) -> count.set(unassigned.stream().filter(e -> e.entityType() == type).count()));
        cost.set(unassigned.stream().mapToDouble(UnassignedEntity::cost).sum());
    }
}
