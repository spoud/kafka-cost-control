package io.spoud.kcc.aggregator.stream.weighting;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Splits a topic's authoritative broker-side byte total {@code B(t)} across the consumers of that topic,
 * weighted by their measured consumption instead of splitting it evenly.
 *
 * <p>This is the pure, deterministic core of the fair-split feature — no Kafka, no I/O — so it can be tested
 * exhaustively with plain data. Given:</p>
 * <ul>
 *     <li>{@code total} — the authoritative topic byte total for the window (e.g. {@code sent_bytes}). This is
 *         the amount that gets billed; the split only decides <em>who</em> pays which fraction of it.</li>
 *     <li>{@code reported} — per-principal weights measured by some provider (KIP-714, offset-progress, …),
 *         already deduplicated to the best tier per principal (see {@link TopicWeightAccumulator}).</li>
 *     <li>{@code roster} — the set of principals authorized to consume the topic (ACL-derived readers/writers).
 *         Used to discover non-reporting consumers so they don't get a free ride during the migration.</li>
 * </ul>
 *
 * <h2>The rule ("Option B")</h2>
 * With reporters {@code R} contributing {@code S = Σ c(p)} and {@code n} non-reporters, each non-reporter is
 * imputed the mean reporter intensity {@code c̄ = S/|R|} (for {@link ImputationMode#MEAN_REPORTER}). Then, with
 * {@code W = S + n·c̄}:
 * <pre>
 *   cost(reporter p)      = total · c(p) / W
 *   cost(non-reporter p)  = total · c̄   / W
 * </pre>
 * The imputation factor cancels out as {@code n → 0}, so at full telemetry coverage this reduces to the exact
 * weighted split {@code total · c(p) / S}. When no one reports it degrades to the legacy even split. The result
 * always sums back to {@code total} (cost conservation), so the aggregate bill is never distorted.
 */
public class ConsumptionWeightSplitter {

    /** A single principal's measured consumption weight and the tier of the signal it came from. */
    public record Weight(double bytes, WeightTier tier) {
    }

    /**
     * @param allocations principal → allocated share of {@code total} (sums to {@code total}, modulo rounding).
     * @param coverage    fraction of the consumption mass backed by a real measured signal rather than imputed
     *                    (1.0 = every consumer reported, 0.0 = nobody did). A confidence/dispute-defense figure.
     */
    public record Result(Map<String, Double> allocations, double coverage) {
    }

    public Result split(double total,
                        Map<String, Weight> reported,
                        Collection<String> roster,
                        ImputationMode mode,
                        String fallbackPrincipal) {
        Map<String, Double> out = new HashMap<>();
        if (total <= 0 || Double.isNaN(total)) {
            // Nothing to bill for this window.
            return new Result(out, 0.0);
        }

        // Sanitize reporter weights: clamp negatives/NaN to 0. A reporter that reported exactly 0 is still a
        // reporter (it consumed nothing) and stays in R so it dilutes the mean correctly.
        Map<String, Double> rep = new HashMap<>();
        if (reported != null) {
            for (Map.Entry<String, Weight> e : reported.entrySet()) {
                double b = e.getValue() == null ? 0.0 : e.getValue().bytes();
                rep.put(e.getKey(), (Double.isNaN(b) || b < 0) ? 0.0 : b);
            }
        }

        Set<String> rosterSet = roster == null ? new HashSet<>() : new HashSet<>(roster);
        // Non-reporters = authorized consumers that produced no signal. Reporters may be absent from the roster
        // (e.g. incomplete ACL data); we trust the measured signal and keep them regardless.
        Set<String> nonReporters = new HashSet<>(rosterSet);
        nonReporters.removeAll(rep.keySet());

        double sumReported = rep.values().stream().mapToDouble(Double::doubleValue).sum();
        int numReporters = rep.size();
        int numNonReporters = nonReporters.size();

        if (numReporters == 0 || sumReported <= 0) {
            // No usable measured signal → fall back to the legacy behaviour: even split across the roster,
            // or, if we don't even know the roster, dump everything on the fallback principal.
            if (rosterSet.isEmpty()) {
                out.put(fallbackPrincipal, total);
            } else {
                double each = total / rosterSet.size();
                for (String p : rosterSet) {
                    out.put(p, each);
                }
            }
            return new Result(out, 0.0);
        }

        double meanReporter = sumReported / numReporters;
        double imputedWeight = (mode == ImputationMode.ZERO) ? 0.0 : meanReporter;
        double totalWeight = sumReported + numNonReporters * imputedWeight;

        // Reporters: measured ratio of the total weight.
        for (Map.Entry<String, Double> e : rep.entrySet()) {
            out.merge(e.getKey(), total * e.getValue() / totalWeight, Double::sum);
        }

        // Non-reporters: imputed share (skipped entirely in ZERO mode).
        if (imputedWeight > 0 && numNonReporters > 0) {
            if (mode == ImputationMode.FALLBACK_PRINCIPAL) {
                double residual = total * (numNonReporters * imputedWeight) / totalWeight;
                out.merge(fallbackPrincipal, residual, Double::sum);
            } else {
                double each = total * imputedWeight / totalWeight;
                for (String p : nonReporters) {
                    out.merge(p, each, Double::sum);
                }
            }
        }

        // Coverage is computed with mean-imputation sizing regardless of mode, so it honestly reflects how much
        // of the consumption mass was actually measured (in ZERO mode reporters still absorb everything, but the
        // presence of non-reporters should lower confidence).
        double massDenominator = sumReported + numNonReporters * meanReporter;
        double coverage = massDenominator > 0 ? sumReported / massDenominator : 0.0;
        return new Result(out, coverage);
    }
}
