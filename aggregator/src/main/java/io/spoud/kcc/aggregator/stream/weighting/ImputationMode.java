package io.spoud.kcc.aggregator.stream.weighting;

/**
 * How to treat consumers that are on a topic's roster (authorized readers/writers) but did not report any
 * usage signal in a window. This is the "Option B" residual policy: reporters are always billed by their
 * measured ratio; this enum only governs how the leftover share is handled.
 *
 * <p>All modes conserve the authoritative topic total and converge to the same exact weighted split as the
 * non-reporting set shrinks to zero (the expected long-term steady state as clients adopt KIP-714).</p>
 */
public enum ImputationMode {
    /**
     * Each non-reporter is imputed the mean intensity of the reporters and pooled into the split. Fairest during
     * the transition and the recommended default. Degrades to an even split when nobody reports.
     */
    MEAN_REPORTER,
    /**
     * Non-reporters get nothing; reporters absorb the entire topic total by their measured ratio. Simplest, but
     * gives idle-looking (non-reporting) consumers a free ride.
     */
    ZERO,
    /**
     * Non-reporters are sized like {@link #MEAN_REPORTER} but their combined share is dumped onto a single
     * fallback principal instead of being spread out. Concentrates unattributed cost so stragglers are visible
     * and pressured to enable telemetry.
     */
    FALLBACK_PRINCIPAL
}
