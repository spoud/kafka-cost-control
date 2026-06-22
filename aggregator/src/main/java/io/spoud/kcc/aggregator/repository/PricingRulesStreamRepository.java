package io.spoud.kcc.aggregator.repository;

import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.kafka.Record;
import io.spoud.kcc.aggregator.data.PricingRuleEntity;
import io.spoud.kcc.aggregator.stream.MetricEnricher;
import io.spoud.kcc.data.PricingRule;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class PricingRulesStreamRepository {

  private Emitter<Record<String, PricingRule>> pricingRulesEmitter;
    private final KafkaStreams kafkaStreams;

    public PricingRulesStreamRepository(
            @Channel("pricing-rules-out") Emitter<Record<String, PricingRule>> pricingRulesEmitter,
            KafkaStreams kafkaStreams) {
        this.pricingRulesEmitter = pricingRulesEmitter;
        this.kafkaStreams = kafkaStreams;
    }

    public PricingRule deletePricingRule(String key) {
    pricingRulesEmitter
        .send(Record.of(key, null))
        .whenComplete(
            (unused, throwable) -> {
              if (throwable != null) {
                Log.errorv(
                    "Unable to send tombstone for pricing rule to kafka for key {0}",
                    key, throwable);
              } else {
                Log.infov("Pricing rule tombstone send on kafka for key {0}", key);
              }
            });
    return null;
  }

  public PricingRule save(PricingRule pricingRule) {
    pricingRulesEmitter
        .send(Record.of(pricingRule.getMetricName(), pricingRule))
        .whenComplete(
            (unused, throwable) -> {
              if (throwable != null) {
                Log.errorv("Unable to send pricing rule to kafka: {0}", pricingRule, throwable);
              } else {
                Log.infov("Pricing rule updated on kafka: {0}", pricingRule);
              }
            });
    return pricingRule;
  }

    public List<PricingRuleEntity> getPricingRules() {
        List<PricingRuleEntity> list = new ArrayList<>();
        try (final KeyValueIterator<String, PricingRule> iterator =
                     getStore().all()) {
            iterator.forEachRemaining(kv -> list.add(PricingRuleEntity.fromAvro(kv.value)));
        }
        return list;
    }

    public ReadOnlyKeyValueStore<String, PricingRule> getStore() {
        while (true) {
            try {
                return kafkaStreams.store(
                        StoreQueryParameters.fromNameAndType(MetricEnricher.PRICING_DATA_TABLE_NAME, QueryableStoreTypes.keyValueStore()));
            } catch (InvalidStateStoreException e) {
                Log.warn("Store not ready yet", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
