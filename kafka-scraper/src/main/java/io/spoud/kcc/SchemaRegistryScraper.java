package io.spoud.kcc;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduler;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.*;

@Startup
@ApplicationScoped
public class SchemaRegistryScraper {

    public static final String GAUGE_NAME = "kafka.topic.schema.count";
    private final MeterRegistry registry;
    private final SchemaRegistryService schemaRegistryService;
    private final Scheduler scheduler;
    private final ScraperConfigProperties configProperties;

    public SchemaRegistryScraper(
            MeterRegistry registry,
            @RestClient SchemaRegistryService schemaRegistryService,
            Scheduler scheduler,
            ScraperConfigProperties configProperties) {
        this.registry = registry;
        this.schemaRegistryService = schemaRegistryService;
        this.scheduler = scheduler;
        this.configProperties = configProperties;
    }

    @PostConstruct
    void scheduleTask() {
        if (configProperties.srEnabled()) {
            String interval = configProperties.srScrapeIntervalSeconds() + "s";
            Log.infov("Scheduling schema registry scraper with an interval of {0}", interval);
            this.scheduler
                    .newJob(this.getClass().getName())
                    .setInterval(interval)
                    .setAsyncTask(scheduledExecution -> this.fetchSchemaCounts())
                    .schedule();
        }
    }

    Uni<Void> fetchSchemaCounts() {
        return schemaRegistryService.getAll()
                .onItem()
                .invoke(schemas -> {
                    removePrevious();
                    var schemaCounts = countSchemasPerTopic(schemas);
                    schemaCounts.keySet().forEach(topic -> registry.gauge(GAUGE_NAME, createTags(topic), schemaCounts.get(topic)));
                }).replaceWithVoid();
    }

    private void removePrevious() {
        registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(GAUGE_NAME))
                .forEach(registry::remove);
    }

    private static Iterable<Tag> createTags(String topic) {
        return List.of(Tag.of("topic", topic));
    }


    private static Map<String, Integer> countSchemasPerTopic(Set<Schema> schemas) {
        // TODO: this will not work with subjects that use a non default naming strategy
        Map<String, Integer> grouped = new HashMap<>();
        for (Schema schema : schemas) {
            if (grouped.containsKey(schema.topic())) {
                grouped.put(schema.topic(), grouped.get(schema.topic()) + 1);
            } else {
                grouped.put(schema.topic(), 1);
            }
        }
        return grouped;
    }


}
