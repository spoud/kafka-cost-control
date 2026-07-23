package io.spoud.kcc.aggregator.stream.weighting;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A single record fed into the per-(topic, total-metric) weighted-split aggregation. It is either the
 * authoritative topic total (e.g. one {@code sent_bytes} sample) or one principal's measured consumption
 * weight. JSON-serialized into the streams repartition topic, hence the mutable public fields + no-arg ctor.
 */
@RegisterForReflection
public class WeightInput {
    /** true = authoritative topic total contribution; false = a per-principal weight contribution. */
    public boolean total;
    /** principal the weight belongs to; null/ignored when {@link #total} is true. */
    public String principal;
    /** the byte value (topic total contribution, or the principal's measured consumption). */
    public double value;
    /** provenance tier of the weight; null/ignored when {@link #total} is true. */
    public WeightTier tier;

    public WeightInput() {
    }

    public static WeightInput forTotal(double value) {
        WeightInput in = new WeightInput();
        in.total = true;
        in.value = value;
        return in;
    }

    public static WeightInput forWeight(String principal, double value, WeightTier tier) {
        WeightInput in = new WeightInput();
        in.total = false;
        in.principal = principal;
        in.value = value;
        in.tier = tier;
        return in;
    }
}
