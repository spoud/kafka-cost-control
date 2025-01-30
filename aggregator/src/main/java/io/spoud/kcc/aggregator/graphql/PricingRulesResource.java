package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.data.PricingRuleEntity;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleDeleteRequest;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleSaveRequest;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.repository.PricingRulesRepository;
import io.spoud.kcc.data.PricingRule;
import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.util.List;

@GraphQLApi
@RequiredArgsConstructor
public class PricingRulesResource {
    private final PricingRulesRepository pricingRulesRepository;

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
}
