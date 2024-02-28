package io.spoud.kcc;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.scheduler.Scheduler;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;

class SchemaRegistryScraperTest {
    private static MeterRegistry registry;
    private static SchemaRegistryService schemaRegistryService;
    private static Scheduler scheduler;
    private static ScraperConfigProperties configProperties;
    private static Scheduler.JobDefinition jobDefinition;

    @BeforeEach
    void setUp() {
        registry = spy(mock(MockMeterRegistry.class));
        schemaRegistryService = mock(SchemaRegistryService.class);

        scheduler = mock(Scheduler.class);
        jobDefinition = mock(Scheduler.JobDefinition.class);
        when(scheduler.newJob(anyString())).thenReturn(jobDefinition);
        when(jobDefinition.setInterval(anyString())).thenReturn(jobDefinition);
        when(jobDefinition.setAsyncTask(any())).thenReturn(jobDefinition);

        configProperties = mock(ScraperConfigProperties.class);
        when(configProperties.srEnabled()).thenReturn(true);
        when(configProperties.srScrapeIntervalSeconds()).thenReturn(10);
        when(configProperties.kafkaEnabled()).thenReturn(true);
        when(configProperties.kafkaScrapeIntervalSeconds()).thenReturn(10);
    }

    @Test
    @DisplayName("should schedule task with configured interval")
    void scheduleTask() {
        var schemaRegistryScraper = new SchemaRegistryScraper(registry, schemaRegistryService, scheduler, configProperties);
        schemaRegistryScraper.scheduleTask();
        verify(jobDefinition).setInterval("10s");
        verify(jobDefinition).schedule();
    }

    @Test
    @DisplayName("should correctly calculate schemas per topic")
    void fetchSchemaCounts() {
        var topicName = "some-topic-name-value";
        var responseUni = Uni.createFrom().item(Set.of(
                new Schema(topicName + "-key", 2),
                new Schema(topicName + "-value", 1),
                new Schema("EntityType", 1)
        ));
        when(schemaRegistryService.getAll()).thenReturn(responseUni);

        var schemaRegistryScraper = new SchemaRegistryScraper(registry, schemaRegistryService, scheduler, configProperties);
        schemaRegistryScraper.fetchSchemaCounts().await().atMost(Duration.ofSeconds(1));

        verify(registry).gauge(SchemaRegistryScraper.GAUGE_NAME, List.of(Tag.of("topic", topicName)), 2L);
    }

}
