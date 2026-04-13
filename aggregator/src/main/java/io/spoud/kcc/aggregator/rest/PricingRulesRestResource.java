package io.spoud.kcc.aggregator.rest;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.data.PricingRuleEntity;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleDeleteRequest;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleSaveRequest;
import io.spoud.kcc.aggregator.repository.PricingRulesRepository;
import io.spoud.kcc.data.PricingRule;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Path("/pricing-rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
public class PricingRulesRestResource {
    private final PricingRulesRepository pricingRulesRepository;

    @GET
    @PermitAll
    public List<PricingRuleEntity> pricingRules() {
        return pricingRulesRepository.getPricingRules();
    }

    @POST
    @Authenticated
    public PricingRuleEntity savePricingRule(PricingRuleSaveRequest request) {
        final PricingRule saved = pricingRulesRepository.save(request.toAvro());
        return PricingRuleEntity.fromAvro(saved);
    }

    @DELETE
    @Authenticated
    public PricingRuleEntity deletePricingRule(PricingRuleDeleteRequest request) {
        final PricingRule deleted = pricingRulesRepository.deletePricingRule(request.metricName());
        return PricingRuleEntity.fromAvro(deleted);
    }
}
