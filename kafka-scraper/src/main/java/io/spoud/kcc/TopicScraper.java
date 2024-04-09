package io.spoud.kcc;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduler;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Collections.singletonList;

@Startup
@ApplicationScoped
public class TopicScraper {

    public static final String GAUGE_NAME = "kafka.topic.partition.count";
    private final AdminClient adminClient;
    private final MeterRegistry registry;
    private final Scheduler scheduler;
    private final ScraperConfigProperties configProperties;


    public TopicScraper(AdminClient adminClient, MeterRegistry registry, Scheduler scheduler, ScraperConfigProperties configProperties) {
        this.adminClient = adminClient;
        this.registry = registry;
        this.scheduler = scheduler;
        this.configProperties = configProperties;
    }

    @PostConstruct
    void scheduleTask() {
        if (configProperties.kafkaEnabled()) {
            String interval = configProperties.kafkaScrapeIntervalSeconds() + "s";
            Log.infov("Scheduling topic scraper with an interval of {0}", interval);
            this.scheduler
                    .newJob(this.getClass().getName())
                    .setInterval(interval)
                    .setTask(scheduledExecution -> this.fetchTopicInformation())
                    .schedule();
        }
    }

    void fetchTopicInformation() {
        try {
            var topics = adminClient.listTopics().names().get();
            var topicDescriptions = adminClient.describeTopics(topics).allTopicNames().get(10, TimeUnit.SECONDS);
            removePrevious();
            topicDescriptions.values().forEach(this::createGaugeMetric);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private void removePrevious() {
        registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(GAUGE_NAME))
                .forEach(registry::remove);
    }

    private void createGaugeMetric(TopicDescription topicDescription) {
        this.registry.gauge(
                GAUGE_NAME,
                singletonList(Tag.of("topic", topicDescription.name())),
                topicDescription.partitions().size()
        );
    }


}
