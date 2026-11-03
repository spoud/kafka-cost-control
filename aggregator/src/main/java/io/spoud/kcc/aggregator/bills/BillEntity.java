package io.spoud.kcc.aggregator.bills;

import io.spoud.kcc.aggregator.stream.serialization.AvroTrust;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.spoud.kcc.data.Bill;
import io.spoud.kcc.data.BillOtherLine;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** What the provider billed for one month (UTC), in dollars, for one cluster or all of them. */
@RegisterForReflection
public record BillEntity(
        @Description("The billed month, yyyy-MM") @NonNull String month,
        @Description("The cluster the bill is for, as the context value cluster; null: all clusters") String cluster,
        @Description("End (exclusive) of what the amounts cover, for a month-to-date bill; null: the whole month")
        Instant coveredUntil,
        @Description("The bill, line by line, each with how it is shared") @NonNull List<@NonNull BillLineEntity> lines,
        @NonNull Instant updatedAt,
        String updatedBy) {

    static {
        AvroTrust.ensure();
    }

    /** The topic key: the month, with the cluster for a cluster's bill. */
    public static String key(String month, String cluster) {
        return cluster == null ? month : month + ":" + cluster;
    }

    public String key() {
        return key(month, cluster);
    }

    public static BillEntity fromAvro(Bill bill) {
        var lines = new ArrayList<BillLineEntity>();
        bill.getLines().forEach(line -> lines.add(BillLineEntity.fromAvro(line)));
        lines.addAll(linesBeforeLines(bill));
        return new BillEntity(bill.getMonth(), bill.getCluster(), bill.getCoveredUntil(), List.copyOf(lines),
                bill.getUpdatedAt(), bill.getUpdatedBy());
    }

    /**
     * A bill saved before lines: its four fixed amounts were Confluent's, each shared by the metric
     * named here; an empty one was estimated, as a line without an amount is now.
     */
    private static List<BillLineEntity> linesBeforeLines(Bill bill) {
        if (bill.getNetworkWrite() == null && bill.getNetworkRead() == null && bill.getStorage() == null
                && bill.getPartitions() == null && bill.getOtherLines().isEmpty()) {
            return List.of();
        }
        var lines = new ArrayList<BillLineEntity>(List.of(
                BillLineEntity.byMetrics("Network write", bill.getNetworkWrite(), "confluent_kafka_server_request_bytes"),
                BillLineEntity.byMetrics("Network read", bill.getNetworkRead(), "confluent_kafka_server_response_bytes"),
                BillLineEntity.byMetrics("Storage", bill.getStorage(), "confluent_kafka_server_retained_bytes"),
                BillLineEntity.byMetrics("Partitions", bill.getPartitions(), "kafka_topic_partition_count")));
        for (BillOtherLine other : bill.getOtherLines()) {
            lines.add(BillLineEntity.other(other.getDescription(), other.getAmount(),
                    BillLineEntity.Allocation.valueOf(other.getAllocation()), other.getContext()));
        }
        return lines;
    }

    public Bill toAvro() {
        return Bill.newBuilder()
                .setMonth(month)
                .setCluster(cluster)
                .setCoveredUntil(coveredUntil)
                .setLines(lines.stream().map(BillLineEntity::toAvro).toList())
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
