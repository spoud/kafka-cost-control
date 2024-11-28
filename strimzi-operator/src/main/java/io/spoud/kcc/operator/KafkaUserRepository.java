package io.spoud.kcc.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.cache.CacheResult;
import io.strimzi.api.kafka.model.user.KafkaUser;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;

@ApplicationScoped
public class KafkaUserRepository {
    public static final String CACHE_NAME = "kafka-users";

    private final KubernetesClient client;
    private final OperatorConfig config;

    public KafkaUserRepository(KubernetesClient client, OperatorConfig config) {
        this.client = client;
        this.config = config;
    }

    /**
     * Get all Kafka users in the configured namespace. See {@link OperatorConfig#namespace()} for the namespace
     * that will be used. To avoid unnecessary calls to the Kubernetes API, the result is cached.
     * The cache is supposed to be invalidated whenever a KafkaUser resource is created, updated or deleted.
     * See {@link KafkaUserReconciler} for the cache invalidation.
     *
     * @return a collection of KafkaUser resources
     */
    @CacheResult(cacheName = CACHE_NAME)
    public Collection<KafkaUser> getAllUsers() {
        return client.resources(KafkaUser.class).inNamespace(config.namespace()).list().getItems();
    }
}
