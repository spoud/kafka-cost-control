package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.identity.SecurityIdentity;
import io.spoud.kcc.aggregator.auth.AccessRules;
import io.spoud.kcc.aggregator.bills.BillEntity;
import io.spoud.kcc.aggregator.bills.BillLine;
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
    @Description("Saves a month's bill, replacing the month's previous one")
    public @NonNull BillEntity saveBill(@NonNull BillSaveRequest request) {
        var saved = billsRepository.save(request.toEntity(Instant.now(), AccessRules.displayName(identity)));
        costsView.refresh(); // so the next cost query already uses it
        return saved;
    }

    @Mutation("deleteBill")
    public BillEntity deleteBill(@NonNull @Name("month") String month) {
        var deleted = billsRepository.delete(month).orElse(null);
        costsView.refresh();
        return deleted;
    }

    @Query("billRates")
    @Description("A bill's usage lines per unit of the usage measured in the hours it covers: the rates to price the hours after it with")
    public @NonNull List<@NonNull BillRate> billRates(@NonNull @Name("month") String month) {
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("The month must look like 2026-09.");
        }
        BillEntity bill = billsRepository.byMonth().get(parsed);
        if (bill == null) {
            throw new BadRequestException("There is no bill for " + parsed + ".");
        }
        var usage = aggregatedMetricsRepository.usageInBill(bill);
        return Arrays.stream(BillLine.values())
                .map(line -> {
                    Double amount = line.amount(bill);
                    return amount == null ? null : BillRate.of(line.metric(), amount, usage.getOrDefault(line.metric(), 0.0));
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Query("billedCosts")
    @Description("Costs in cents from the monthly bills, with the rate card where no bill applies")
    public @NonNull BilledCostResponse billedCosts(@NonNull BilledCostRequest request) {
        return aggregatedMetricsRepository.calculateBilledCosts(request, billsRepository.byMonth(), Instant.now());
    }
}
