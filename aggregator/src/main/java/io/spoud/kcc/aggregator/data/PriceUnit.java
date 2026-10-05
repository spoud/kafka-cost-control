package io.spoud.kcc.aggregator.data;

import org.eclipse.microprofile.graphql.Description;

/**
 * What a pricing rule's price is per. Metrics carry raw values (bytes, counts), so a price per GB
 * becomes a cost factor per byte. Every window is one hour, so a stored-bytes metric's hourly max
 * priced per GB_HOUR is the GB-hours a provider bills.
 */
@Description("What a pricing rule's price is per")
public enum PriceUnit {
    @Description("Per GB of the metric's value, 1 GB = 2^30 bytes as Confluent bills (e.g. network bytes)")
    GB(1L << 30),
    @Description("Per GB held for one hourly window, 1 GB = 2^30 bytes (e.g. retained bytes)")
    GB_HOUR(1L << 30),
    @Description("Per unit of the metric's value for one hourly window (e.g. per partition)")
    UNIT(1);

    private final double valuePerUnit;

    PriceUnit(double valuePerUnit) {
        this.valuePerUnit = valuePerUnit;
    }

    /** An amount in this unit, in units of the metric's raw value (e.g. GB to bytes). */
    public double toRawValue(double amount) {
        return amount * valuePerUnit;
    }

    /** The cost per unit of the metric's raw value. */
    public double costFactor(double price, double multiplier) {
        return price * multiplier / valuePerUnit;
    }

    /** The unit stored on a rule, or null for rules saved before units existed or unknown names. */
    public static PriceUnit fromStored(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
