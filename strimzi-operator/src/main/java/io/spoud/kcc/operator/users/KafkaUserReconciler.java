package io.spoud.kcc.operator.users;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkus.cache.CacheInvalidateAll;
import io.spoud.kcc.operator.topics.KafkaTopicReconciler;
import io.strimzi.api.kafka.model.user.KafkaUser;
import jakarta.enterprise.context.ApplicationScoped;

import static io.spoud.kcc.operator.users.KafkaUserRepository.CACHE_NAME;

@ApplicationScoped
public class KafkaUserReconciler implements Reconciler<KafkaUser> {

    private final KafkaTopicReconciler kafkaTopicReconciler;

    public KafkaUserReconciler(KafkaTopicReconciler kafkaTopicReconciler) {
        this.kafkaTopicReconciler = kafkaTopicReconciler;
    }

    @Override
    @CacheInvalidateAll(cacheName = CACHE_NAME) // Invalidate the cache of KafkaUserRepository
    public UpdateControl<KafkaUser> reconcile(KafkaUser kafkaUser, Context<KafkaUser> context) throws Exception {
        kafkaTopicReconciler.reconcileAllTopics(); // regenerate contexts since user permissions may have changed
        return UpdateControl.noUpdate();
    }
}
