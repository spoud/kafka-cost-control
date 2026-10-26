package io.spoud.kcc.aggregator.bills;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A month's bill as entered, in dollars. Saving a month's bill for the same cluster replaces it. */
@RegisterForReflection
public record BillSaveRequest(
        @Description("The billed month, yyyy-MM, in UTC") @NonNull String month,
        @Description("The cluster the bill is for, as the context value cluster. Empty: all clusters.")
        String cluster,
        @Description("For a month-to-date bill: the end (exclusive) of what the amounts cover. Empty: the whole month.")
        Instant coveredUntil,
        @Description("The bill, line by line; an amount may be negative for a credit")
        List<BillLineRequest> lines) {

    public BillEntity toEntity(Instant now, String user) {
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(month == null ? "" : month.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("The month must look like 2026-09.");
        }
        Instant start = BillEntity.monthStart(parsed);
        Instant end = BillEntity.monthEnd(parsed);
        if (coveredUntil != null && (!coveredUntil.isAfter(start) || coveredUntil.isAfter(end))) {
            throw new BadRequestException("The bill must cover part of " + parsed + ": up to a time after its start and no later than its end.");
        }
        List<BillLineEntity> checked = lines == null ? List.of() : lines.stream().map(BillSaveRequest::checked).toList();
        if (checked.stream().allMatch(line -> line.amount() == null)) {
            throw new BadRequestException("Enter at least one amount.");
        }
        Map<String, String> lineOfMetric = new HashMap<>();
        for (var line : checked) {
            for (var metric : line.metrics()) {
                String other = lineOfMetric.putIfAbsent(metric, line.description());
                if (other != null) {
                    throw new BadRequestException(metric + " is on both \"" + other + "\" and \"" + line.description()
                            + "\": a metric can share only one line.");
                }
            }
        }
        String clusterName = cluster == null || cluster.isBlank() ? null : cluster.trim();
        Instant until = coveredUntil == null || coveredUntil.equals(end) ? null : coveredUntil;
        return new BillEntity(parsed.toString(), clusterName, until, checked, now, user);
    }

    private static BillLineEntity checked(BillLineRequest line) {
        if (line.description() == null || line.description().isBlank()) {
            throw new BadRequestException("Every line needs a description, e.g. Storage or Support.");
        }
        String description = line.description().trim();
        if (line.allocation() == null) {
            throw new BadRequestException("Say how \"" + description + "\" is shared: by metrics, to a context, spread by usage, or shared.");
        }
        return switch (line.allocation()) {
            case METRICS -> {
                var metrics = new LinkedHashSet<String>();
                if (line.metrics() != null) {
                    line.metrics().stream().filter(m -> m != null && !m.isBlank()).map(String::trim).forEach(metrics::add);
                }
                if (metrics.isEmpty()) {
                    throw new BadRequestException("\"" + description + "\" is shared by metrics: pick at least one.");
                }
                if (line.amount() != null && line.amount() < 0) {
                    throw new BadRequestException("\"" + description + "\" is shared by metrics: its amount must be 0 or more.");
                }
                yield new BillLineEntity(description, line.amount(), line.allocation(), List.copyOf(metrics), null);
            }
            case CONTEXT -> {
                Map<String, String> context = new TreeMap<>();
                if (line.context() != null) {
                    for (var pair : line.context()) {
                        if (pair != null && pair.key() != null && !pair.key().isBlank() && pair.value() != null && !pair.value().isBlank()) {
                            context.put(pair.key().trim(), pair.value().trim());
                        }
                    }
                }
                if (context.isEmpty()) {
                    throw new BadRequestException("\"" + description + "\" goes to a context: give at least one key and value, e.g. tenant=data-platform.");
                }
                yield BillLineEntity.other(description, amountOf(line, description), line.allocation(), context);
            }
            case USAGE, SHARED -> BillLineEntity.other(description, amountOf(line, description), line.allocation(), null);
        };
    }

    private static double amountOf(BillLineRequest line, String description) {
        if (line.amount() == null) {
            throw new BadRequestException("\"" + description + "\" needs an amount.");
        }
        return line.amount();
    }
}
