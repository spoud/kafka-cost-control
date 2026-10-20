package io.spoud.kcc.aggregator.bills;

import io.spoud.kcc.aggregator.bills.BillLineEntity.Allocation;
import io.spoud.kcc.data.Bill;
import io.spoud.kcc.data.BillOtherLine;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class BillSaveRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    @Test
    void savesAMonthWithItsLines() {
        var bill = request(" 2026-09 ", null, null,
                metrics("Storage", 8.121, " kafka_log_log_size "),
                metrics("Network", 0.49, "kafka_server_brokertopicmetrics_bytesin_total", "kafka_server_brokertopicmetrics_bytesout_total", " "))
                .toEntity(NOW, "ana@example.com");

        assertThat(bill.month()).isEqualTo("2026-09");
        assertThat(bill.cluster()).isNull();
        assertThat(bill.key()).isEqualTo("2026-09");
        assertThat(bill.coveredUntil()).isNull();
        assertThat(bill.lines()).extracting(BillLineEntity::description).containsExactly("Storage", "Network");
        assertThat(bill.lines().getFirst().metrics()).containsExactly("kafka_log_log_size");
        // blank metrics are dropped
        assertThat(bill.lines().get(1).metrics()).containsExactly(
                "kafka_server_brokertopicmetrics_bytesin_total", "kafka_server_brokertopicmetrics_bytesout_total");
        assertThat(bill.updatedBy()).isEqualTo("ana@example.com");
        assertThat(bill.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void aClustersBillIsKeyedByMonthAndCluster() {
        var bill = request("2026-10", " poc-cluster ", null, metrics("Storage", 1.0, "kafka_log_log_size")).toEntity(NOW, null);

        assertThat(bill.cluster()).isEqualTo("poc-cluster");
        assertThat(bill.key()).isEqualTo("2026-10:poc-cluster");
        assertThat(request("2026-10", " ", null, metrics("Storage", 1.0, "m")).toEntity(NOW, null).cluster()).isNull();
    }

    @Test
    void aMonthToDateBillKeepsWhereItStops() {
        var bill = request("2026-10", null, Instant.parse("2026-10-06T00:00:00Z"), metrics("Network write", 0.8163, "m"))
                .toEntity(NOW, null);

        assertThat(bill.coveredUntil()).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"));
        assertThat(BillEntity.billedUntil(bill)).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"));
    }

    @Test
    void coveringTheWholeMonthIsTheSameAsNoEnd() {
        var bill = request("2026-09", null, Instant.parse("2026-10-01T00:00:00Z"), metrics("Storage", 1.0, "m"))
                .toEntity(NOW, null);

        assertThat(bill.coveredUntil()).isNull();
        assertThat(BillEntity.billedUntil(bill)).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void aMetricsLineMayLeaveItsAmountEmptyToBeEstimated() {
        var bill = request("2026-09", null, null, metrics("Storage", 1.0, "a"), metrics("Partitions", null, "b"))
                .toEntity(NOW, null);

        assertThat(bill.lines().get(1).amount()).isNull();
    }

    @Test
    void otherLinesKeepWhereTheyGoAndMayBeACredit() {
        var bill = request("2026-09", null, null,
                new BillLineRequest(" Connect ", 12.0, Allocation.CONTEXT, List.of("ignored"), List.of(
                        new BillLineRequest.ContextPair(" application ", " etl "), new BillLineRequest.ContextPair("tenant", " "))),
                new BillLineRequest("Support", 50.0, Allocation.USAGE, null, List.of(new BillLineRequest.ContextPair("ignored", "x"))),
                new BillLineRequest("Promo credit", -5.0, Allocation.SHARED, null, null))
                .toEntity(NOW, null);

        assertThat(bill.lines()).extracting(BillLineEntity::description).containsExactly("Connect", "Support", "Promo credit");
        // blank pairs are dropped; a context only belongs to a CONTEXT line, metrics only to a METRICS line
        assertThat(bill.lines().getFirst().context()).containsExactly(Map.entry("application", "etl"));
        assertThat(bill.lines().getFirst().metrics()).isEmpty();
        assertThat(bill.lines().get(1).context()).isNull();
        assertThat(bill.lines().get(2).amount()).isEqualTo(-5.0);
    }

    @Test
    void aLineNeedsWhatItsAllocationNeeds() {
        assertThatThrownBy(() -> request("2026-09", null, null, new BillLineRequest(" ", 1.0, Allocation.SHARED, null, null))
                .toEntity(NOW, null)).isInstanceOf(BadRequestException.class).hasMessageContaining("description");
        assertThatThrownBy(() -> request("2026-09", null, null, new BillLineRequest("Connect", 1.0, null, null, null))
                .toEntity(NOW, null)).isInstanceOf(BadRequestException.class).hasMessageContaining("how \"Connect\" is shared");
        assertThatThrownBy(() -> request("2026-09", null, null, new BillLineRequest("Connect", 1.0, Allocation.CONTEXT, null, null))
                .toEntity(NOW, null)).isInstanceOf(BadRequestException.class).hasMessageContaining("tenant=data-platform");
        assertThatThrownBy(() -> request("2026-09", null, null, metrics("Storage", 1.0, " "))
                .toEntity(NOW, null)).isInstanceOf(BadRequestException.class).hasMessageContaining("pick at least one");
        assertThatThrownBy(() -> request("2026-09", null, null, metrics("Storage", 1.0, "m"),
                new BillLineRequest("Support", null, Allocation.USAGE, null, null))
                .toEntity(NOW, null)).isInstanceOf(BadRequestException.class).hasMessageContaining("\"Support\" needs an amount");
    }

    @Test
    void aMetricSharesOnlyOneLine() {
        assertThatThrownBy(() -> request("2026-09", null, null, metrics("Network write", 1.0, "bytes"), metrics("Network", 2.0, "bytes"))
                .toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("bytes is on both \"Network write\" and \"Network\"");
    }

    @Test
    void refusesWhatCantBeABill() {
        assertThatThrownBy(() -> request("September", null, null, metrics("Storage", 1.0, "m")).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("2026-09");
        assertThatThrownBy(() -> new BillSaveRequest("2026-09", null, null, null).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("at least one amount");
        assertThatThrownBy(() -> request("2026-09", null, null, metrics("Storage", null, "m")).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("at least one amount");
        assertThatThrownBy(() -> request("2026-09", null, null, metrics("Storage", -1.0, "m")).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("0 or more");
        assertThatThrownBy(() -> request("2026-09", null, Instant.parse("2026-10-02T00:00:00Z"), metrics("Storage", 1.0, "m")).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("cover part of 2026-09");
        assertThatThrownBy(() -> request("2026-09", null, Instant.parse("2026-09-01T00:00:00Z"), metrics("Storage", 1.0, "m")).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aBillSavedBeforeLinesIsReadAsLinesWithConfluentsMetrics() {
        var old = Bill.newBuilder()
                .setMonth("2026-09")
                .setNetworkWrite(0.27).setNetworkRead(0.22).setStorage(8.12).setPartitions(null)
                .setOtherLines(List.of(BillOtherLine.newBuilder().setDescription("Support").setAmount(5.0)
                        .setAllocation("CONTEXT").setContext(Map.of("tenant", "data-platform")).build()))
                .setUpdatedAt(NOW)
                .build();

        var bill = BillEntity.fromAvro(old);

        assertThat(bill.cluster()).isNull();
        assertThat(bill.lines()).extracting(BillLineEntity::description, BillLineEntity::amount, BillLineEntity::allocation)
                .containsExactly(
                        tuple("Network write", 0.27, Allocation.METRICS),
                        tuple("Network read", 0.22, Allocation.METRICS),
                        tuple("Storage", 8.12, Allocation.METRICS),
                        // empty, so estimated, as before
                        tuple("Partitions", null, Allocation.METRICS),
                        tuple("Support", 5.0, Allocation.CONTEXT));
        assertThat(bill.lines().getFirst().metrics()).containsExactly("confluent_kafka_server_request_bytes");
        assertThat(bill.lines().get(4).context()).containsEntry("tenant", "data-platform");
        // saved again, it has only lines
        assertThat(bill.toAvro().getNetworkWrite()).isNull();
        assertThat(BillEntity.fromAvro(bill.toAvro())).isEqualTo(bill);
    }

    private static BillSaveRequest request(String month, String cluster, Instant coveredUntil, BillLineRequest... lines) {
        return new BillSaveRequest(month, cluster, coveredUntil, List.of(lines));
    }

    private static BillLineRequest metrics(String description, Double amount, String... metrics) {
        return new BillLineRequest(description, amount, Allocation.METRICS, List.of(metrics), null);
    }
}
