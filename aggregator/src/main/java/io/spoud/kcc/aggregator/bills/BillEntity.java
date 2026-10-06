package io.spoud.kcc.aggregator.bills;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.Bill;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/** What the provider billed for one month (UTC), in dollars. A missing line wasn't entered. */
@RegisterForReflection
public record BillEntity(
        @Description("The billed month, yyyy-MM") @NonNull String month,
        @Description("End (exclusive) of what the amounts cover, for a month-to-date bill; null: the whole month")
        Instant coveredUntil,
        Double networkWrite,
        Double networkRead,
        Double storage,
        Double partitions,
        @Description("Everything else on the bill, shown as platform / shared rather than split by usage")
        Double other,
        @NonNull Instant updatedAt,
        String updatedBy) {

    public static BillEntity fromAvro(Bill bill) {
        return new BillEntity(bill.getMonth(), bill.getCoveredUntil(), bill.getNetworkWrite(),
                bill.getNetworkRead(), bill.getStorage(), bill.getPartitions(), bill.getOther(),
                bill.getUpdatedAt(), bill.getUpdatedBy());
    }

    public Bill toAvro() {
        return Bill.newBuilder()
                .setMonth(month)
                .setCoveredUntil(coveredUntil)
                .setNetworkWrite(networkWrite)
                .setNetworkRead(networkRead)
                .setStorage(storage)
                .setPartitions(partitions)
                .setOther(other)
                .setUpdatedAt(updatedAt)
                .setUpdatedBy(updatedBy)
                .build();
    }

    public static Instant monthStart(YearMonth month) {
        return month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public static Instant monthEnd(YearMonth month) {
        return monthStart(month.plusMonths(1));
    }

    /** Where the amounts stop: {@code coveredUntil}, or the end of the month. */
    public static Instant billedUntil(BillEntity bill) {
        Instant end = monthEnd(YearMonth.parse(bill.month()));
        return bill.coveredUntil() == null || bill.coveredUntil().isAfter(end) ? end : bill.coveredUntil();
    }
}
