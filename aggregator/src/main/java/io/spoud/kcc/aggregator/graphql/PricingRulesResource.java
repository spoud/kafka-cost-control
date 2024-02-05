package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.data.PricingRuleEntity;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleDeleteRequest;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleSaveRequest;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.repository.PricingRulesRepository;
import io.spoud.kcc.aggregator.stream.KafkaStreamManager;
import io.spoud.kcc.data.PricingRule;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@RequiredArgsConstructor
@Authenticated
public class PricingRulesResource {
  private final KafkaStreamManager kafkaStreamStarter;
  private final PricingRulesRepository pricingRulesRepository;
  private final MetricNameRepository metricNameRepository;

  @Query("metricNames")
  public @NonNull List<@NonNull MetricNameEntity> metricNames() {
    return metricNameRepository.getMetricNames();
  }

  @Query("pricingRules")
  public @NonNull List<@NonNull PricingRuleEntity> pricingRules() {
      return pricingRulesRepository.getPricingRules();
  }

  @Mutation("savePricingRule")
  public @NonNull PricingRuleEntity savePricingRule(PricingRuleSaveRequest request) {
    final PricingRule saved = pricingRulesRepository.save(request.toAvro());
    return PricingRuleEntity.fromAvro(saved);
  }

  @Mutation("deletePricingRule")
  public PricingRuleEntity deletePricingRule(PricingRuleDeleteRequest request) {
    final PricingRule deleted = pricingRulesRepository.deletePricingRule(request.metricName());
    return PricingRuleEntity.fromAvro(deleted);
  }
}
