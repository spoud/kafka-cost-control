package io.spoud.kcc.aggregator.data;

import io.spoud.kcc.data.PricingRule;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricePeriodsTest {

    private static final Instant OCT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NOV = Instant.parse("2026-11-01T00:00:00Z");

    private static PricingRule price(double factor) {
        return PricingRule.newBuilder().setCreationTime(Instant.EPOCH).setMetricName("m")
                .setBaseCost(0).setCostFactor(factor).setPrice(factor * 1000).setPriceUnit("UNIT")
                .setEarlierPrices(List.of()).build();
    }

    @Test
    void aRuleWithoutDatesAppliesAlways() {
        assertThat(PricePeriods.at(price(0.1), Instant.EPOCH)).contains(new PricePeriods.Rate(0, 0.1));
    }

    @Test
    void aNewPriceFromADateKeepsTheOldOneBeforeIt() {
        var rule = PricePeriods.from(price(0.1), price(0.2), OCT);

        assertThat(PricePeriods.at(rule, OCT.minusSeconds(1))).contains(new PricePeriods.Rate(0, 0.1));
        assertThat(PricePeriods.at(rule, OCT)).contains(new PricePeriods.Rate(0, 0.2));
        assertThat(rule.getEarlierPrices()).singleElement().satisfies(p -> {
            assertThat(p.getValidFrom()).isNull();
            assertThat(p.getValidUntil()).isEqualTo(OCT);
            assertThat(p.getPrice()).isEqualTo(100.0);
        });
    }

    @Test
    void aFirstRuleFromADateHasNoPriceBefore() {
        var rule = PricePeriods.from(null, price(0.2), OCT);

        assertThat(PricePeriods.at(rule, OCT.minusSeconds(1))).isEmpty();
        assertThat(PricePeriods.at(rule, OCT)).isPresent();
    }

    @Test
    void correctingReplacesTheCurrentPriceAndKeepsHistory() {
        var dated = PricePeriods.from(price(0.1), price(0.2), OCT);
        var corrected = PricePeriods.corrected(dated, price(0.3));

        assertThat(PricePeriods.at(corrected, OCT)).contains(new PricePeriods.Rate(0, 0.3));
        assertThat(PricePeriods.at(corrected, OCT.minusSeconds(1))).contains(new PricePeriods.Rate(0, 0.1));
    }

    @Test
    void aNewPriceMustStartAfterTheCurrentOne() {
        var dated = PricePeriods.from(price(0.1), price(0.2), NOV);

        assertThatThrownBy(() -> PricePeriods.from(dated, price(0.3), OCT))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("correct it");
    }

    @Test
    void undoingBringsThePreviousPriceBack() {
        var twice = PricePeriods.from(PricePeriods.from(price(0.1), price(0.2), OCT), price(0.3), NOV);

        var undone = PricePeriods.undoLastChange(twice, Instant.now());

        assertThat(PricePeriods.at(undone, NOV)).contains(new PricePeriods.Rate(0, 0.2));
        assertThat(undone.getValidFrom()).isEqualTo(OCT);
        assertThat(PricePeriods.at(undone, OCT.minusSeconds(1))).contains(new PricePeriods.Rate(0, 0.1));
        assertThatThrownBy(() -> PricePeriods.undoLastChange(price(0.1), Instant.now()))
                .isInstanceOf(BadRequestException.class);
    }
}
