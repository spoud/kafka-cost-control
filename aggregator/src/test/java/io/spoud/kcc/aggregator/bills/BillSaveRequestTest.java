package io.spoud.kcc.aggregator.bills;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillSaveRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    @Test
    void savesAMonthWithItsAmounts() {
        var bill = new BillSaveRequest(" 2026-09 ", null, 0.2685, 0.2206, 8.121, 22.5008, null)
                .toEntity(NOW, "ana@example.com");

        assertThat(bill.month()).isEqualTo("2026-09");
        assertThat(bill.coveredUntil()).isNull();
        assertThat(bill.partitions()).isEqualTo(22.5008);
        assertThat(bill.updatedBy()).isEqualTo("ana@example.com");
        assertThat(bill.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void aMonthToDateBillKeepsWhereItStops() {
        var bill = new BillSaveRequest("2026-10", Instant.parse("2026-10-06T00:00:00Z"), 0.8163, null, null, null, null)
                .toEntity(NOW, null);

        assertThat(bill.coveredUntil()).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"));
        assertThat(BillEntity.billedUntil(bill)).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"));
    }

    @Test
    void coveringTheWholeMonthIsTheSameAsNoEnd() {
        var bill = new BillSaveRequest("2026-09", Instant.parse("2026-10-01T00:00:00Z"), 1.0, null, null, null, null)
                .toEntity(NOW, null);

        assertThat(bill.coveredUntil()).isNull();
        assertThat(BillEntity.billedUntil(bill)).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void otherLinesKeepWhereTheyGoAndMayBeACredit() {
        var bill = new BillSaveRequest("2026-09", null, null, null, null, null, List.of(
                new OtherLine(" Connect ", 12.0, OtherLine.Allocation.CONTEXT, Map.of(" application ", " etl ", "tenant", " ")),
                new OtherLine("Support", 50.0, OtherLine.Allocation.USAGE, Map.of("ignored", "x")),
                new OtherLine("Promo credit", -5.0, OtherLine.Allocation.SHARED, null)))
                .toEntity(NOW, null);

        assertThat(bill.otherLines()).extracting(OtherLine::description).containsExactly("Connect", "Support", "Promo credit");
        // blank pairs are dropped; a context only belongs to a CONTEXT line
        assertThat(bill.otherLines().getFirst().context()).containsExactly(Map.entry("application", "etl"));
        assertThat(bill.otherLines().get(1).context()).isNull();
        assertThat(bill.otherLines().get(2).amount()).isEqualTo(-5.0);
    }

    @Test
    void anOtherLineNeedsADescriptionAndAContextWhenItGoesToOne() {
        assertThatThrownBy(() -> new BillSaveRequest("2026-09", null, null, null, null, null,
                List.of(new OtherLine(" ", 1.0, OtherLine.Allocation.SHARED, null))).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("description");
        assertThatThrownBy(() -> new BillSaveRequest("2026-09", null, null, null, null, null,
                List.of(new OtherLine("Connect", 1.0, OtherLine.Allocation.CONTEXT, Map.of()))).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("tenant=data-platform");
    }

    @Test
    void refusesWhatCantBeABill() {
        assertThatThrownBy(() -> new BillSaveRequest("September", null, 1.0, null, null, null, null).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("2026-09");
        assertThatThrownBy(() -> new BillSaveRequest("2026-09", null, null, null, null, null, null).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("at least one amount");
        assertThatThrownBy(() -> new BillSaveRequest("2026-09", null, -1.0, null, null, null, null).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("0 or more");
        assertThatThrownBy(() -> new BillSaveRequest("2026-09", Instant.parse("2026-10-02T00:00:00Z"), 1.0, null, null, null, null).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("cover part of 2026-09");
        assertThatThrownBy(() -> new BillSaveRequest("2026-09", Instant.parse("2026-09-01T00:00:00Z"), 1.0, null, null, null, null).toEntity(NOW, null))
                .isInstanceOf(BadRequestException.class);
    }
}
