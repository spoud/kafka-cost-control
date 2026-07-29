package io.spoud.kcc.operator;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.operator.topics.KafkaTopicReconciler;
import io.spoud.kcc.operator.users.KafkaUserRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared by both the v1 and v1beta2 KafkaUser/KafkaTopic JOSDK reconcilers (exactly one of which is
 * registered in any given build -- see the {@code cc.strimzi.crd-version} build-time property). A watch
 * event from whichever one is active marks contexts dirty; this class does the actual recalculation on a
 * timer so JOSDK reconcile() callbacks stay cheap and don't need direct Kafka-producer access.
 */
@ApplicationScoped
public class ContextRecalculationScheduler {

    private final KafkaTopicReconciler kafkaTopicReconciler;
    private final KafkaUserRepository kafkaUserRepository;
    private final AtomicBoolean recalculateContexts = new AtomicBoolean(false);

    public ContextRecalculationScheduler(KafkaTopicReconciler kafkaTopicReconciler, KafkaUserRepository kafkaUserRepository) {
        this.kafkaTopicReconciler = kafkaTopicReconciler;
        this.kafkaUserRepository = kafkaUserRepository;
    }

    /**
     * Called by the active KafkaUserReconciler variant when a KafkaUser resource changes.
     */
    public void markChanged() {
        kafkaUserRepository.invalidateCache();
        recalculateContexts.set(true);
    }

    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void recalculateIfNeeded() {
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

    @Scheduled(every = "PT1H", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void periodicRecalculation() {
        Log.infov("Triggering periodic context recalculation for ALL topics");
        markChanged();
    }

    /**
     * Runs a recalculation unconditionally, bypassing the dirty flag. Used by tests, which don't rely on
     * JOSDK watch events (start-operator is disabled in the test profile).
     */
    public void reconcileNow() {
        kafkaUserRepository.invalidateCache();
        kafkaTopicReconciler.reconcileAllTopics();
    }
}
