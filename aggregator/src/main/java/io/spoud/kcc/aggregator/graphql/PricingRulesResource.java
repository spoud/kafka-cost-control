package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.data.PricingRuleEntity;
import io.spoud.kcc.aggregator.graphql.data.CalculateTopDownInput;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleDeleteRequest;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleSaveRequest;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.repository.PricingRulesRepository;
import io.spoud.kcc.data.PricingRule;
import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.javamoney.moneta.FastMoney;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@GraphQLApi
@RequiredArgsConstructor
public class PricingRulesResource {
    private final PricingRulesRepository pricingRulesRepository;

    // TODO -> auslagern, quick and dirty atm
    private final MetricNameRepository metricNameRepository;
    private final AggregatedMetricsRepository aggregatedMetricsRepository;

    @PermitAll
    @Query("pricingRules")
    public @NonNull List<@NonNull PricingRuleEntity> pricingRules() {
        return pricingRulesRepository.getPricingRules();
    }

    @Authenticated
    @Mutation("savePricingRule")
    public @NonNull PricingRuleEntity savePricingRule(PricingRuleSaveRequest request) {
        final PricingRule saved = pricingRulesRepository.save(request.toAvro());
        return PricingRuleEntity.fromAvro(saved);
    }

    @Authenticated
    @Mutation("deletePricingRule")
    public PricingRuleEntity deletePricingRule(PricingRuleDeleteRequest request) {
        final PricingRule deleted = pricingRulesRepository.deletePricingRule(request.metricName());
        return PricingRuleEntity.fromAvro(deleted);
    }

    /**
     * End price (e.g. from confluent) to a distribution (according to consumption percentage)
     */
    @Authenticated
    @Query("calculateTopDown")
    public @NonNull List<String> calculateTopDown(CalculateTopDownInput request) {
        List<MetricNameEntity> metricNames = metricNameRepository.getMetricNames();
        List<PricingRuleEntity> pricingRules = pricingRulesRepository.getPricingRules();

        Map<String, PricingRuleEntity> pricesByMetricName = pricingRules.stream()
                .filter(x -> x.baseCost() != 0d || x.costFactor() != 0d)
                .collect(Collectors.toMap(
                        PricingRuleEntity::metricName,
                        x -> x
                ));

        Map<String, Double> aggregatedValue = aggregatedMetricsRepository.getAggregatedValue(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now(),
                pricesByMetricName.keySet());

        Map<String, Double> prices = new HashMap<>();
        aggregatedValue.forEach(
                (metricName, total) -> {
                    PricingRuleEntity pricingRuleEntity = pricesByMetricName.get(metricName);
                    double price = pricingRuleEntity.costFactor() * total;
                    prices.put(metricName, price);
                }
        );

        FastMoney usd = FastMoney.of(0.25, "XXX");
        FastMoney divide = usd.divide(2);

        return prices.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .toList();
    }
}
/**
 * Vielleicht ein Brainstorming zu Preisberechnung:
 *
 * 2 Varianten:
 * * Cloud: Jemand kommt mit dem Endbetrag und will diesen aufgeschlüsselt haben
 * * On-Prem: Jemand will fiktive Kosten mittels den gesammelten Metriken haben
 *
 * * Endbetrag von confluent cloud ist halb halb aufgeschlüsselt, siehe https://docs.confluent.io/cloud/current/api.html#tag/Costs-(billingv1)/operation/listBillingV1Costs
 *
 * Bottom-up:
 * Aggregation = MAX: \sum (metrik * pricing rules (per window)
 * Aggregation = SUM: letzte metrik * pricing rule
 *
 * Top-Down:
 * Input all values
 * (hidden) Mapping for confluent enum -> kcc-metrik
 * Divide & split by usage (don't use pricing rules!)
 *
 */
