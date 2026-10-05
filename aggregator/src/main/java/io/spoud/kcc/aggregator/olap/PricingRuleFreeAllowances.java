package io.spoud.kcc.aggregator.olap;

import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.data.PricingRuleEntity;
import io.spoud.kcc.aggregator.repository.PricingRulesStreamRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Free allowances from the pricing rules. The repository is looked up lazily: it depends on Kafka
 * Streams, whose topology depends on the OLAP repository that uses this.
 */
@ApplicationScoped
public class PricingRuleFreeAllowances implements FreeAllowances {
    private final Instance<PricingRulesStreamRepository> pricingRules;

    public PricingRuleFreeAllowances(Instance<PricingRulesStreamRepository> pricingRules) {
        this.pricingRules = pricingRules;
    }

    @Override
    public Map<String, Allowance> current() {
        try {
            return pricingRules.get().getPricingRules().stream()
                    .filter(rule -> rule.freePerWindow() != null && rule.freePerWindow() > 0 && rule.priceUnit() != null)
                    .collect(Collectors.toMap(PricingRuleEntity::metricName, PricingRuleFreeAllowances::allowance));
        } catch (RuntimeException e) {
            // e.g. the rule store is restoring after a restart; the next flush of the window applies it
            Log.debugf("Pricing rules not readable for free allowances: %s", e.getMessage());
            return Map.of();
        }
    }

    public static Allowance allowance(PricingRuleEntity rule) {
        return new Allowance(rule.baseCost(), rule.costFactor(), rule.priceUnit().toRawValue(rule.freePerWindow()));
    }
}
