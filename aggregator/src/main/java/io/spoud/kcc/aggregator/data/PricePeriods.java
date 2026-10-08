package io.spoud.kcc.aggregator.data;

import io.spoud.kcc.aggregator.stream.serialization.AvroTrust;
import io.spoud.kcc.data.PricePeriod;
import io.spoud.kcc.data.PricingRule;
import jakarta.ws.rs.BadRequestException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A pricing rule's prices over time: the current price (the rule's own fields, from
 * {@code valid_from} on) and the earlier ones. Saving either corrects the current price or starts a
 * new one from a date, which keeps the price before it for the hours before it.
 */
public final class PricePeriods {

    /** What a price charges: {@code baseCost + costFactor * value}. */
    public record Rate(double baseCost, double costFactor) {
    }

    static {
        AvroTrust.ensure();
    }

    private PricePeriods() {
    }

    /** The rate for an hour starting at {@code time}; empty before the rule's first price. */
    public static Optional<Rate> at(PricingRule rule, Instant time) {
        if (rule.getValidFrom() == null || !time.isBefore(rule.getValidFrom())) {
            return Optional.of(new Rate(rule.getBaseCost(), rule.getCostFactor()));
        }
        for (PricePeriod period : rule.getEarlierPrices()) {
            boolean started = period.getValidFrom() == null || !time.isBefore(period.getValidFrom());
            if (started && time.isBefore(period.getValidUntil())) {
                return Optional.of(new Rate(period.getBaseCost(), period.getCostFactor()));
            }
        }
        return Optional.empty();
    }

    /** Replaces the current price everywhere it applies; earlier prices stay. */
    public static PricingRule corrected(PricingRule existing, PricingRule price) {
        if (existing == null) {
            return price;
        }
        return PricingRule.newBuilder(price)
                .setValidFrom(existing.getValidFrom())
                .setEarlierPrices(existing.getEarlierPrices())
                .build();
    }

    /** Starts {@code price} at {@code from}; the current price keeps the hours before it. */
    public static PricingRule from(PricingRule existing, PricingRule price, Instant from) {
        if (existing == null) {
            return PricingRule.newBuilder(price).setValidFrom(from).setEarlierPrices(List.of()).build();
        }
        if (existing.getValidFrom() != null && !from.isAfter(existing.getValidFrom())) {
            throw new BadRequestException("A new price must start after the current one, which started "
                    + existing.getValidFrom() + ". To change the current price itself, correct it.");
        }
        var earlier = new ArrayList<>(existing.getEarlierPrices());
        earlier.add(PricePeriod.newBuilder()
                .setValidFrom(existing.getValidFrom())
                .setValidUntil(from)
                .setBaseCost(existing.getBaseCost())
                .setCostFactor(existing.getCostFactor())
                .setPrice(existing.getPrice())
                .setPriceUnit(existing.getPriceUnit())
                .setMultiplier(existing.getMultiplier())
                .setMultiplierLabel(existing.getMultiplierLabel())
                .build());
        return PricingRule.newBuilder(price).setValidFrom(from).setEarlierPrices(earlier).build();
    }

    /** Drops the current price and makes the last earlier one current again, open-ended. */
    public static PricingRule undoLastChange(PricingRule existing, Instant now) {
        if (existing == null || existing.getEarlierPrices().isEmpty()) {
            throw new BadRequestException("This rule has no earlier price to go back to.");
        }
        var earlier = new ArrayList<>(existing.getEarlierPrices());
        PricePeriod previous = earlier.removeLast();
        return PricingRule.newBuilder(existing)
                .setCreationTime(now)
                .setValidFrom(previous.getValidFrom())
                .setBaseCost(previous.getBaseCost())
                .setCostFactor(previous.getCostFactor())
                .setPrice(previous.getPrice())
                .setPriceUnit(previous.getPriceUnit())
                .setMultiplier(previous.getMultiplier())
                .setMultiplierLabel(previous.getMultiplierLabel())
                .setEarlierPrices(earlier)
                .build();
    }
}
