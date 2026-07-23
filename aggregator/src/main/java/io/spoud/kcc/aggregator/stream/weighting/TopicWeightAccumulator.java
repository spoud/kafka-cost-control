package io.spoud.kcc.aggregator.stream.weighting;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

/**
 * Windowed accumulator for one (topic, total-metric) pair. Collects the authoritative topic total and the
 * best-tier measured weight per principal seen in the window. JSON-serialized into the streams state store,
 * hence the mutable public fields + no-arg ctor.
 *
 * <p>Tier precedence is enforced here so a principal is never double-counted when multiple providers report
 * it: a higher-tier signal replaces a lower one, and same-tier contributions sum (multiple samples in a
 * window). This is what makes the cross-platform signal waterfall safe to mix.</p>
 */
@RegisterForReflection
public class TopicWeightAccumulator {

    /** Provenance-tagged consumption weight for a single principal within the window. */
    @RegisterForReflection
    public static class WeightEntry {
        public double bytes;
        public WeightTier tier;

        public WeightEntry() {
        }

        public WeightEntry(double bytes, WeightTier tier) {
            this.bytes = bytes;
            this.tier = tier;
        }
    }

    /** Authoritative topic byte total for the window (summed across samples). */
    public double total = 0.0;
    /** principal → best-tier measured weight. */
    public Map<String, WeightEntry> weights = new HashMap<>();

    public TopicWeightAccumulator() {
    }

    public TopicWeightAccumulator add(WeightInput input) {
        if (input.total) {
            total += input.value;
        } else if (input.principal != null) {
            addWeight(input.principal, input.value, input.tier == null ? WeightTier.T2_OFFSET_PROGRESS : input.tier);
        }
        return this;
    }

    void addWeight(String principal, double value, WeightTier tier) {
        WeightEntry current = weights.get(principal);
        if (current == null || current.tier == null) {
            weights.put(principal, new WeightEntry(value, tier));
        } else if (tier.priority > current.tier.priority) {
            // A better signal supersedes whatever we had.
            weights.put(principal, new WeightEntry(value, tier));
        } else if (tier.priority == current.tier.priority) {
            // Same-quality samples within the window add up.
            current.bytes += value;
        }
        // Lower-tier signal for a principal we already measured better: ignore.
    }
}
