package io.spoud.kcc.operator.users;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.operator.topics.KafkaTopicReconciler;
import io.strimzi.api.kafka.model.user.KafkaUser;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.spoud.kcc.operator.users.KafkaUserRepository.CACHE_NAME;

@ApplicationScoped
public class KafkaUserReconciler implements Reconciler<KafkaUser> {

    private final KafkaTopicReconciler kafkaTopicReconciler;
    private final AtomicBoolean recalculateContexts = new AtomicBoolean(false);

    public KafkaUserReconciler(KafkaTopicReconciler kafkaTopicReconciler) {
        this.kafkaTopicReconciler = kafkaTopicReconciler;
    }

    @Override
    @CacheInvalidateAll(cacheName = CACHE_NAME) // Invalidate the cache of KafkaUserRepository
    public UpdateControl<KafkaUser> reconcile(KafkaUser kafkaUser, Context<KafkaUser> context) throws Exception {
        Log.debugv("Detected change in KafkaUser {0}, marking contexts for recalculation", kafkaUser.getMetadata().getName());
        recalculateContexts.set(true);
        return UpdateControl.noUpdate();
    }

    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void recalculateContexts() {
        if (recalculateContexts.compareAndSet(true, false)) {
            Log.infov("Recalculating contexts after KafkaUser change");
            try {
                kafkaTopicReconciler.reconcileAllTopics(); // regenerate contexts since user permissions may have changed
            } catch (Exception e) {
                Log.errorv("Error while recalculating contexts after KafkaUser change: {0}. Recalculation will be retried.", e.getMessage(), e);
                recalculateContexts.set(true);
            }
        }
    }
}
