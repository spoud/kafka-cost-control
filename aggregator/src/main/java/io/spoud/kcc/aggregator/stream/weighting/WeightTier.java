package io.spoud.kcc.aggregator.stream.weighting;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Quality tier of a per-(topic, principal) consumption weight. Higher {@link #priority} wins when several
 * sources report a weight for the same principal on the same topic within a window (see
 * {@link TopicWeightAccumulator}). The tiers form the cross-platform "signal waterfall": each platform
 * populates whichever tiers it can, and the weighting engine consumes the best available per principal.
 *
 * <ul>
 *     <li>{@link #T1_CLIENT_TELEMETRY} – KIP-714 client telemetry, real per-(topic, principal) bytes consumed.
 *         Highest fidelity, requires a modern, opt-in client. Self-managed only (for now).</li>
 *     <li>{@link #T2_OFFSET_PROGRESS} – committed-offset advancement per (group, topic). Version-independent,
 *         broker-authoritative, works on both self-managed and Confluent Cloud. The recommended backbone.</li>
 *     <li>{@link #T3_PRINCIPAL_TOTAL} – coarse per-principal totals (e.g. Confluent {@code response_bytes}),
 *         no per-topic breakdown. Weakest signal, used only when nothing better exists.</li>
 * </ul>
 */
@RegisterForReflection
public enum WeightTier {
    T3_PRINCIPAL_TOTAL(1),
    T2_OFFSET_PROGRESS(2),
    T1_CLIENT_TELEMETRY(3);

    public final int priority;

    WeightTier(int priority) {
        this.priority = priority;
    }

    /**
     * Parse a tier from a raw tag value. Accepts the full enum name ("T1_CLIENT_TELEMETRY"), the short code
     * ("T1", "T2", "T3", case-insensitive) or {@code null}. Anything unrecognized falls back to {@code def}.
     */
    public static WeightTier fromString(String value, WeightTier def) {
        if (value == null || value.isBlank()) {
            return def;
        }
        String v = value.trim().toUpperCase();
        switch (v) {
            case "T1":
                return T1_CLIENT_TELEMETRY;
            case "T2":
                return T2_OFFSET_PROGRESS;
            case "T3":
                return T3_PRINCIPAL_TOTAL;
            default:
                try {
                    return WeightTier.valueOf(v);
                } catch (IllegalArgumentException e) {
                    return def;
                }
        }
    }
}
