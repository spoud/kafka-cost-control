package io.spoud.kcc;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class KafkaClients {


    private final Map<String, Object> config;

    public KafkaClients(@Identifier("default-kafka-broker") Map<String, Object> config) {
        this.config = config;
    }

    @Produces
    AdminClient getAdmin() {
        return KafkaAdminClient.create(config);
    }

    @Produces
    @Singleton
    public MeterFilter addCommonTags(AdminClient adminClient) throws ExecutionException, InterruptedException, TimeoutException {
        var clusterId = adminClient.describeCluster().clusterId().get(10, TimeUnit.SECONDS);
        return MeterFilter.commonTags(Arrays.asList(Tag.of("cluster_id", clusterId)));
    }


}
