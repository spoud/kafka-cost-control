package io.spoud.kcc.aggregator.bills;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** A month's bill as entered, in dollars. Saving a month that has a bill replaces it. */
@RegisterForReflection
public record BillSaveRequest(
        @Description("The billed month, yyyy-MM, in UTC") @NonNull String month,
        @Description("For a month-to-date bill: the end (exclusive) of what the amounts cover. Empty: the whole month.")
        Instant coveredUntil,
        Double networkWrite,
        Double networkRead,
        Double storage,
        Double partitions,
        @Description("Everything else on the bill, line by line; an amount may be negative for a credit")
        List<OtherLine> otherLines) {

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
        if (Stream.of(networkWrite, networkRead, storage, partitions).anyMatch(a -> a != null && a < 0)) {
            throw new BadRequestException("Network, storage and partition amounts must be 0 or more.");
        }
        List<OtherLine> others = otherLines == null ? List.of() : otherLines.stream().map(BillSaveRequest::checked).toList();
        if (Stream.of(networkWrite, networkRead, storage, partitions).allMatch(a -> a == null) && others.isEmpty()) {
            throw new BadRequestException("Enter at least one amount.");
        }
        Instant until = coveredUntil == null || coveredUntil.equals(end) ? null : coveredUntil;
        return new BillEntity(parsed.toString(), until, networkWrite, networkRead, storage, partitions, others, now, user);
    }

    private static OtherLine checked(OtherLine line) {
        if (line.description() == null || line.description().isBlank()) {
            throw new BadRequestException("Every other line needs a description, e.g. Connect or Support.");
        }
        if (line.allocation() == null) {
            throw new BadRequestException("Say where \"" + line.description().trim() + "\" goes: a context, spread by usage, or shared.");
        }
        Map<String, String> context = null;
        if (line.allocation() == OtherLine.Allocation.CONTEXT) {
            context = new TreeMap<>();
            if (line.context() != null) {
                for (var entry : line.context().entrySet()) {
                    if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null && !entry.getValue().isBlank()) {
                        context.put(entry.getKey().trim(), entry.getValue().trim());
                    }
                }
            }
            if (context.isEmpty()) {
                throw new BadRequestException("\"" + line.description().trim() + "\" goes to a context: give at least one key and value, e.g. tenant=data-platform.");
            }
        }
        return new OtherLine(line.description().trim(), line.amount(), line.allocation(), context);
    }
}
