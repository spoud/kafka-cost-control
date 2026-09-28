/**
 * Compact number formatting for axis ticks and tooltips. SI-compact rather than byte units, since
 * the axis already carries the unit name.
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
    // below a thousand the compact form adds nothing, and would round away real differences
    return Math.abs(value) < 1000 ? precise.format(value) : compact.format(value);
}

/** Percentages in normalized mode, where one decimal is as much precision as the axis can show. */
export function formatPercent(value: number | null | undefined): string {
    if (value === null || value === undefined || !Number.isFinite(value)) {
        return '—';
    }
    return `${value.toFixed(1)}%`;
}

/** What a chart's values measure: usage (named by the axis) or money from the pricing rules. */
export type ChartUnit = 'usage' | 'currency';

const cents = new Intl.NumberFormat(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
});

/** 1.5 -> "$1.50", 12345 -> "$12.3K". Non-finite values render as an em dash, never "NaN". */
export function formatCurrency(value: number | null | undefined): string {
    if (value === null || value === undefined || !Number.isFinite(value)) {
        return '—';
    }
    return '$' + (Math.abs(value) < 1000 ? cents.format(value) : compact.format(value));
}
