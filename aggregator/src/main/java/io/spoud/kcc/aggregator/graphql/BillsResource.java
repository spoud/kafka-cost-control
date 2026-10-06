package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.identity.SecurityIdentity;
import io.spoud.kcc.aggregator.auth.AccessRules;
import io.spoud.kcc.aggregator.bills.BillEntity;
import io.spoud.kcc.aggregator.bills.BillSaveRequest;
import io.spoud.kcc.aggregator.bills.BillsRepository;
import io.spoud.kcc.aggregator.graphql.data.BilledCostRequest;
import io.spoud.kcc.aggregator.graphql.data.BilledCostResponse;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.time.Instant;
import java.util.List;

/** The monthly bills, and the costs they lead to. */
@GraphQLApi
// @RequestScoped: reads the per-request SecurityIdentity, like UserResource
@RequestScoped
public class BillsResource {

    @Inject
    BillsRepository billsRepository;

    @Inject
    AggregatedMetricsRepository aggregatedMetricsRepository;

    @Inject
    SecurityIdentity identity;

    @Query("bills")
    @Description("All bills, newest month first, in dollars")
    public @NonNull List<@NonNull BillEntity> bills() {
        return billsRepository.all();
    }

    @Mutation("saveBill")
    @Description("Saves a month's bill, replacing the month's previous one")
    public @NonNull BillEntity saveBill(@NonNull BillSaveRequest request) {
        return billsRepository.save(request.toEntity(Instant.now(), AccessRules.displayName(identity)));
    }

    @Mutation("deleteBill")
    public BillEntity deleteBill(@NonNull @Name("month") String month) {
        return billsRepository.delete(month).orElse(null);
    }

    @Query("billedCosts")
    @Description("Costs in cents from the monthly bills, with the rate card where no bill applies")
    public @NonNull BilledCostResponse billedCosts(@NonNull BilledCostRequest request) {
        return aggregatedMetricsRepository.calculateBilledCosts(request, billsRepository.byMonth(), Instant.now());
    }
}
