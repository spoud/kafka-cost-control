package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.identity.SecurityIdentity;
import io.spoud.kcc.aggregator.auth.AccessRules;
import io.spoud.kcc.aggregator.bills.BillEntity;
import io.spoud.kcc.aggregator.bills.BillLineEntity;
import io.spoud.kcc.aggregator.bills.BillRate;
import io.spoud.kcc.aggregator.bills.BillSaveRequest;
import io.spoud.kcc.aggregator.bills.BillsRepository;
import io.spoud.kcc.aggregator.graphql.data.BilledCostRequest;
import io.spoud.kcc.aggregator.graphql.data.BilledCostResponse;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import io.spoud.kcc.aggregator.olap.CostsView;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
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

    @Inject
    CostsView costsView;

    @Query("bills")
    @Description("All bills, newest month first, in dollars")
    public @NonNull List<@NonNull BillEntity> bills() {
        return billsRepository.all();
    }

    @Mutation("saveBill")
    @Description("Saves a month's bill, replacing the one for the same month and cluster")
    public @NonNull BillEntity saveBill(@NonNull BillSaveRequest request) {
        var saved = billsRepository.save(request.toEntity(Instant.now(), AccessRules.displayName(identity)));
        costsView.refresh(); // so the next cost query already uses it
        return saved;
    }

    @Mutation("deleteBill")
    @Description("Deletes a month's bill: the one for all clusters, or a cluster's")
    public BillEntity deleteBill(@NonNull @Name("month") String month, @Name("cluster") String cluster) {
        var deleted = billsRepository.delete(month, blankToNull(cluster)).orElse(null);
        costsView.refresh();
        return deleted;
    }

    @Query("billRates")
    @Description("A bill's METRICS lines per unit of the usage measured in the hours it covers: the rates to price the hours after it with")
    public @NonNull List<@NonNull BillRate> billRates(@NonNull @Name("month") String month, @Name("cluster") String cluster) {
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("The month must look like 2026-09.");
        }
        String clusterName = blankToNull(cluster);
        BillEntity bill = billsRepository.find(parsed.toString(), clusterName).orElseThrow(() -> new BadRequestException(
                "There is no bill for " + parsed + (clusterName == null ? "" : " and cluster " + clusterName) + "."));
        var usage = aggregatedMetricsRepository.usageInBill(bill);
        return bill.lines().stream()
                .filter(line -> line.allocation() == BillLineEntity.Allocation.METRICS && line.amount() != null)
                .flatMap(line -> {
                    double lineUsage = line.metrics().stream().mapToDouble(m -> usage.getOrDefault(m, 0.0)).sum();
                    return line.metrics().stream().map(m -> BillRate.of(m, line.description(), line.amount(), lineUsage));
                })
                .toList();
    }

    @Query("clusters")
    @Description("The clusters in the data, for a cluster's bill")
    public @NonNull List<@NonNull String> clusters() {
        return aggregatedMetricsRepository.clusters();
    }

    @Query("billedCosts")
    @Description("Costs in cents from the monthly bills, with the rate card where no bill applies")
    public @NonNull BilledCostResponse billedCosts(@NonNull BilledCostRequest request) {
        return aggregatedMetricsRepository.calculateBilledCosts(request, billsRepository.all(), Instant.now());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
