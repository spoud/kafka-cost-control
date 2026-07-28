package io.spoud.kcc.operator.users;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.operator.topics.KafkaTopicReconciler;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KafkaUserReconciler {

    private final KafkaTopicReconciler kafkaTopicReconciler;
    private final KafkaUserRepository kafkaUserRepository;

    public KafkaUserReconciler(KafkaTopicReconciler kafkaTopicReconciler, KafkaUserRepository kafkaUserRepository) {
        this.kafkaTopicReconciler = kafkaTopicReconciler;
        this.kafkaUserRepository = kafkaUserRepository;
    }

    /**
     * Re-lists all KafkaUsers/KafkaTopics and recalculates contexts. Runs unconditionally on a fixed
     * cadence rather than being triggered by a KafkaUser watch event, since {@link KafkaTopicReconciler#reconcileAllTopics()}
     * already only publishes when the computed context actually changed.
     */
    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void reconcileNow() {
        kafkaUserRepository.invalidateCache();
        try {
            kafkaTopicReconciler.reconcileAllTopics();
        } catch (Exception e) {
            Log.errorv("Error while recalculating contexts: {0}. Will be retried on the next tick.", e.getMessage(), e);
        }
    }
}
