/**
 * Axis ticks and tooltips get compact numbers — 1k rather than 1000 — because a stacked byte
 * series easily reaches ten digits, and full-length ticks either overlap or push the plot area
 * so far right that the chart stops using its width.
 *
 * Deliberately SI-compact rather than byte units (KB/MB): the axis already carries the unit
 * name, so the suffix only has to convey magnitude.
 */
const compact = new Intl.NumberFormat(undefined, {
    notation: 'compact',
    maximumFractionDigits: 1,
});

const precise = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });

/** 1000 -> "1K", 1234567 -> "1.2M". Non-finite values render as an em dash, never "NaN". */
export function formatCompact(value: number | null | undefined): string {
    if (value === null || value === undefined || !Number.isFinite(value)) {
        return '—';
    }
    // below a thousand the compact form is just the number, and rounding to 1 decimal there
    // would hide differences the user can otherwise see
    return Math.abs(value) < 1000 ? precise.format(value) : compact.format(value);
}

/** Percentages in normalized mode: one decimal is plenty, and the sign is never useful. */
export function formatPercent(value: number | null | undefined): string {
    if (value === null || value === undefined || !Number.isFinite(value)) {
        return '—';
    }
    return `${value.toFixed(1)}%`;
}
