package io.spoud.kcc.operator.users;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import io.quarkus.logging.Log;
import io.spoud.kcc.operator.OperatorConfig;
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
        if ("v1beta2".equals(config.strimziCrdApiVersion())) {
            return client.resources(KafkaUserV1beta2.class).inNamespace(config.namespace()).list().getItems()
                    .stream().map(KafkaUserRepository::toKafkaUser).toList();
        }
        return client.resources(KafkaUser.class).inNamespace(config.namespace()).list().getItems();
    }

    private static KafkaUser toKafkaUser(KafkaUserV1beta2 legacy) {
        KafkaUser user = new KafkaUser();
        user.setMetadata(legacy.getMetadata());
        user.setSpec(legacy.getSpec());
        user.setStatus(legacy.getStatus());
        return user;
    }

    @CacheInvalidateAll(cacheName = CACHE_NAME)
    public void invalidateCache() {
        Log.debug("Invalidating KafkaUser cache");
    }
}
