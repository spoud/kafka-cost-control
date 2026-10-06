package io.spoud.kcc.aggregator.bills;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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
    void aCreditMayBeNegative() {
        var bill = new BillSaveRequest("2026-09", null, null, null, null, null, -5.0).toEntity(NOW, null);

        assertThat(bill.other()).isEqualTo(-5.0);
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
