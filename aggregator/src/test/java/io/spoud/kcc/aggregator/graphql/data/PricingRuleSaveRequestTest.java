package io.spoud.kcc.aggregator.graphql.data;

import io.spoud.kcc.aggregator.data.PriceUnit;
import io.spoud.kcc.aggregator.data.PricingRuleEntity;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PricingRuleSaveRequestTest {

    private static final double GB = 1L << 30;

    private static PricingRuleSaveRequest request(Double costFactor, Double price, PriceUnit unit,
                                                  Double multiplier, String label) {
        return new PricingRuleSaveRequest("m", 0.0, costFactor, price, unit, multiplier, label, null);
    }

    @Test
    void pricePerGbBecomesACostFactorPerByte() {
        var rule = request(null, 0.1495, PriceUnit.GB, null, null).toAvro();

        assertThat(rule.getCostFactor()).isCloseTo(0.1495 / GB, within(1e-24));
        assertThat(rule.getPrice()).isEqualTo(0.1495);
        assertThat(rule.getPriceUnit()).isEqualTo("GB");
        assertThat(rule.getMultiplier()).isNull();
    }

    @Test
    void storagePerReplicaMultipliesThePrice() {
        var rule = request(null, 0.00012603, PriceUnit.GB_HOUR, 3.0, "replicas").toAvro();

        assertThat(rule.getCostFactor()).isCloseTo(3 * 0.00012603 / GB, within(1e-27));
        assertThat(rule.getMultiplierLabel()).isEqualTo("replicas");
    }

    @Test
    void aPerUnitPriceIsTheCostFactor() {
        assertThat(request(null, 0.0046, PriceUnit.UNIT, null, null).toAvro().getCostFactor())
                .isEqualTo(0.0046);
    }

    @Test
    void storesTheFreeAmountPerWindow() {
        var free = new PricingRuleSaveRequest("m", 0.0, null, 0.0046, PriceUnit.UNIT, null, null, 10.0).toAvro();
        var none = new PricingRuleSaveRequest("m", 0.0, null, 0.0046, PriceUnit.UNIT, null, null, 0.0).toAvro();

        assertThat(free.getFreePerWindow()).isEqualTo(10.0);
        assertThat(none.getFreePerWindow()).isNull();
        assertThatThrownBy(() -> new PricingRuleSaveRequest("m", 0.0, null, 1.0, PriceUnit.UNIT, null, null, -1.0).toAvro())
                .isInstanceOf(BadRequestException.class).hasMessageContaining("free amount");
        assertThatThrownBy(() -> new PricingRuleSaveRequest("m", 0.0, 1.0, null, null, null, null, 10.0).toAvro())
                .isInstanceOf(BadRequestException.class).hasMessageContaining("needs a price");
    }

    @Test
    void theFreeAmountIsConvertedToRawUnits() {
        var rule = PricingRuleEntity.fromAvro(new PricingRuleSaveRequest("m", 0.0, null, 0.1495, PriceUnit.GB, null, null, 2.0).toAvro());

        assertThat(io.spoud.kcc.aggregator.olap.PricingRuleFreeAllowances.allowance(rule).freeRaw()).isEqualTo(2.0 * GB);
    }

    @Test
    void aFreeMetricMayCostZero() {
        assertThat(request(null, 0.0, PriceUnit.GB, null, null).toAvro().getCostFactor()).isZero();
    }

    @Test
    void aPriceWinsOverACostFactorSentAlongside() {
        assertThat(request(42.0, 1.0, PriceUnit.UNIT, null, null).toAvro().getCostFactor()).isEqualTo(1.0);
    }

    @Test
    void aBareCostFactorStillWorksAndCarriesNoPrice() {
        var rule = request(1e-10, null, null, null, null).toAvro();

        assertThat(rule.getCostFactor()).isEqualTo(1e-10);
        assertThat(rule.getPrice()).isNull();
        assertThat(rule.getPriceUnit()).isNull();
    }

    @Test
    void aLabelWithoutAMultiplierIsDropped() {
        assertThat(request(null, 1.0, PriceUnit.GB, null, "replicas").toAvro().getMultiplierLabel()).isNull();
    }

    @Test
    void rejectsIncompleteOrNonsensicalPrices() {
        assertThatThrownBy(() -> request(null, 1.0, null, null, null).toAvro())
                .isInstanceOf(BadRequestException.class).hasMessageContaining("priceUnit");
        assertThatThrownBy(() -> request(null, -0.01, PriceUnit.GB, null, null).toAvro())
                .isInstanceOf(BadRequestException.class).hasMessageContaining("0 or more");
        assertThatThrownBy(() -> request(null, 1.0, PriceUnit.GB, 0.0, null).toAvro())
                .isInstanceOf(BadRequestException.class).hasMessageContaining("multiplier");
        assertThatThrownBy(() -> request(1.0, null, PriceUnit.GB, null, null).toAvro())
                .isInstanceOf(BadRequestException.class).hasMessageContaining("needs a price");
        assertThatThrownBy(() -> request(null, null, null, null, null).toAvro())
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void theEntityReadsThePriceBack() {
        var entity = PricingRuleEntity.fromAvro(
                request(null, 0.00012603, PriceUnit.GB_HOUR, 3.0, "replicas").toAvro());

        assertThat(entity.price()).isEqualTo(0.00012603);
        assertThat(entity.priceUnit()).isEqualTo(PriceUnit.GB_HOUR);
        assertThat(entity.multiplier()).isEqualTo(3.0);
        assertThat(entity.multiplierLabel()).isEqualTo("replicas");
    }

    @Test
    void anUnknownStoredUnitReadsAsNoUnit() {
        assertThat(PriceUnit.fromStored("PER_FORTNIGHT")).isNull();
        assertThat(PriceUnit.fromStored(null)).isNull();
    }
}
