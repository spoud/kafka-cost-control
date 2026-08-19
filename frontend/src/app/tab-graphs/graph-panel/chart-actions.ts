import { WritableSignal } from '@angular/core';

/**
 * The slice of a chart component that a host can drive from its own menu.
 *
 * Reporting panels render their chart through `ngComponentOutlet`, so the panel has no template
 * access to it. Rather than have the panel and the chart each grow their own overflow menu — two
 * buttons stacked in the same corner doing related things — the panel reaches the instance through
 * `NgComponentOutlet.componentInstance` and offers these actions alongside its own.
 *
 * Kept deliberately tiny: anything larger and the panel starts knowing how charts work.
 */
export interface ChartActions {
    exportToCsv(): void;
    /** Absent on charts with no normalized mode, such as the pie. */
    normalized?: WritableSignal<boolean>;
    showLegend: WritableSignal<boolean>;
}

export function isChartActions(value: unknown): value is ChartActions {
    return !!value && typeof (value as ChartActions).exportToCsv === 'function';
}
