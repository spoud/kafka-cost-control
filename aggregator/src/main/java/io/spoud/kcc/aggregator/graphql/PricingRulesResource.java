package io.spoud.kcc.aggregator.graphql;

import io.spoud.kcc.aggregator.data.PricePeriods;
import io.spoud.kcc.aggregator.data.PricingRuleEntity;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleDeleteRequest;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleSaveRequest;
import io.spoud.kcc.aggregator.repository.PricingRulesStreamRepository;
import io.spoud.kcc.data.PricingRule;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.time.Instant;
import java.util.List;

@Path("/api/v1/pricing-rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@GraphQLApi
@RequiredArgsConstructor
public class PricingRulesResource {
    private final PricingRulesStreamRepository pricingRulesStreamRepository;

    @GET
    @PermitAll
    @Query("pricingRules")
    public @NonNull List<@NonNull PricingRuleEntity> pricingRules() {
        return pricingRulesStreamRepository.getPricingRules();
    }

    @POST
    @Mutation("savePricingRule")
    public @NonNull PricingRuleEntity savePricingRule(PricingRuleSaveRequest request) {
        PricingRule price = request.toAvro();
        PricingRule existing = pricingRulesStreamRepository.get(price.getMetricName());
        PricingRule rule = request.validFrom() == null
                ? PricePeriods.corrected(existing, price)
                : PricePeriods.from(existing, price, request.validFrom());
        return PricingRuleEntity.fromAvro(pricingRulesStreamRepository.save(rule));
    }

    @POST
    @Path("/undo-last-change")
    @Mutation("undoPricingRuleChange")
    @Description("Drops the current price and makes the previous one current again")
    public @NonNull PricingRuleEntity undoPricingRuleChange(PricingRuleDeleteRequest request) {
        PricingRule existing = pricingRulesStreamRepository.get(request.metricName());
        return PricingRuleEntity.fromAvro(pricingRulesStreamRepository.save(
                PricePeriods.undoLastChange(existing, Instant.now())));
    }

    @DELETE
    @Mutation("deletePricingRule")
    public PricingRuleEntity deletePricingRule(PricingRuleDeleteRequest request) {
        final PricingRule deleted = pricingRulesStreamRepository.deletePricingRule(request.metricName());
        return PricingRuleEntity.fromAvro(deleted);
    }
}
