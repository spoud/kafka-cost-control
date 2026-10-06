package io.spoud.kcc.aggregator.bills;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
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
        @Description("Everything else on the bill; may be negative for a credit")
        Double other) {

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
        if (Stream.of(networkWrite, networkRead, storage, partitions, other).allMatch(a -> a == null)) {
            throw new BadRequestException("Enter at least one amount.");
        }
        Instant until = coveredUntil == null || coveredUntil.equals(end) ? null : coveredUntil;
        return new BillEntity(parsed.toString(), until, networkWrite, networkRead, storage, partitions, other, now, user);
    }
}
