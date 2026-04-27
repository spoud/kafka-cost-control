package io.spoud.kcc.aggregator.graphql;

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
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.util.List;

@Path("/api/v1/pricing-rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@GraphQLApi
@RequiredArgsConstructor
public class PricingRulesResource {
    private final PricingRulesRepository pricingRulesRepository;

    @GET
    @PermitAll
    @Query("pricingRules")
    public @NonNull List<@NonNull PricingRuleEntity> pricingRules() {
        return pricingRulesRepository.getPricingRules();
    }

    @POST
    @Authenticated
    @Mutation("savePricingRule")
    public @NonNull PricingRuleEntity savePricingRule(PricingRuleSaveRequest request) {
        final PricingRule saved = pricingRulesRepository.save(request.toAvro());
        return PricingRuleEntity.fromAvro(saved);
    }

    @DELETE
    @Authenticated
    @Mutation("deletePricingRule")
    public PricingRuleEntity deletePricingRule(PricingRuleDeleteRequest request) {
        final PricingRule deleted = pricingRulesRepository.deletePricingRule(request.metricName());
        return PricingRuleEntity.fromAvro(deleted);
    }
}
