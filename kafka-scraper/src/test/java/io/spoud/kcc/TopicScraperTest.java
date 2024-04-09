package io.spoud.kcc;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.scheduler.Scheduler;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

class TopicScraperTest {
    private AdminClient adminClient;
    private MeterRegistry registry;
    private Scheduler scheduler;
    private ScraperConfigProperties configProperties;
    private static Scheduler.JobDefinition jobDefinition;

    @BeforeEach
    void setUp() {
        adminClient = mock(AdminClient.class);
        registry = spy(new SimpleMeterRegistry());

        scheduler = mock(Scheduler.class);
        jobDefinition = mock(Scheduler.JobDefinition.class);
        when(scheduler.newJob(anyString())).thenReturn(jobDefinition);
        when(jobDefinition.setInterval(anyString())).thenReturn(jobDefinition);
        when(jobDefinition.setTask(any(Consumer.class))).thenReturn(jobDefinition);

        configProperties = mock(ScraperConfigProperties.class);
        when(configProperties.srEnabled()).thenReturn(true);
        when(configProperties.srScrapeIntervalSeconds()).thenReturn(10);
        when(configProperties.kafkaEnabled()).thenReturn(true);
        when(configProperties.kafkaScrapeIntervalSeconds()).thenReturn(10);
    }

    @Test
    @DisplayName("should schedule task with configured interval")
    void scheduleTask() {
        var topicScraper = new TopicScraper(adminClient, registry, scheduler, configProperties);
        topicScraper.scheduleTask();
        verify(jobDefinition).setInterval("10s");
        verify(jobDefinition).schedule();
    }

    @Test
    @DisplayName("should correctly count partitions per topic")
    void fetchTopicInformation() throws ExecutionException, InterruptedException, TimeoutException {
        var topicScraper = new TopicScraper(adminClient, registry, scheduler, configProperties);
        var topicDescription = mock(TopicDescription.class);
        when(adminClient.listTopics()).thenReturn(mock(ListTopicsResult.class));
        when(adminClient.listTopics().names()).thenReturn(mock(KafkaFuture.class));
        when(adminClient.listTopics().names().get()).thenReturn(Set.of("topic1"));
        when(adminClient.describeTopics(Set.of("topic1"))).thenReturn(mock(DescribeTopicsResult.class));
        when(adminClient.describeTopics(Set.of("topic1")).allTopicNames()).thenReturn(mock(KafkaFuture.class));
        when(adminClient.describeTopics(Set.of("topic1")).allTopicNames().get(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(Map.of("topic1", topicDescription));
        when(topicDescription.partitions()).thenReturn(List.of(mock(TopicPartitionInfo.class)));
        when(topicDescription.name()).thenReturn("topic1");
        topicScraper.fetchTopicInformation();
        verify(registry, times(1)).gauge("kafka.topic.partition.count", List.of(Tag.of("topic", "topic1")), 1);
    }
}
