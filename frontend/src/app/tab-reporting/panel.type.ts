import { BarChartPanelComponent } from './panel/panels/bar-chart-panel/bar-chart-panel.component';
import { PieChartPanelComponent } from './panel/panels/pie-chart-panel/pie-chart-panel.component';
import { EChartsType } from 'echarts/core';
import { LineChartPanelComponent } from './panel/panels/line-chart-panel/line-chart-panel.component';
import { isRevivableDate } from '../common/persisted-state';

export type PanelType = 'StackedBar' | 'Line' | 'Pie';

/** Labels for the chart-type picker in the panel editor, keyed by the type stored on the panel. */
export const PANEL_TYPE_LABELS: ReadonlyArray<{ value: PanelType; label: string }> = [
    { value: 'StackedBar', label: 'Stacked bar' },
    { value: 'Line', label: 'Area / line' },
    { value: 'Pie', label: 'Pie' },
];

export type Panel = {
    id: string;
    title: string;
    description?: string;
    type: PanelType;

    // graph filter
    from: Date;
    to?: Date;
    metricName?: string;
    groupByContext: string[];

    eChartsInstance?: EChartsType;

    // display, currently unused
    rows?: number;
    columns?: number;
};

export const TYPE_TO_COMPONENT_MAPPING = {
    StackedBar: BarChartPanelComponent,
    Line: LineChartPanelComponent,
    Pie: PieChartPanelComponent,
};

/**
 * Derived from the mapping rather than repeated, so renaming a chart type cannot leave a stale
 * literal behind. A persisted panel whose type no longer exists would otherwise resolve to an
 * undefined component and render as a silently blank card.
 */
export function isPanelType(value: unknown): value is Panel['type'] {
    return typeof value === 'string' && value in TYPE_TO_COMPONENT_MAPPING;
}

/** Shape check for a panel read back out of localStorage. See common/persisted-state. */
export function isPanel(value: unknown): value is Panel {
    if (!value || typeof value !== 'object') {
        return false;
    }
    const candidate = value as Partial<Panel>;
    return (
        typeof candidate.id === 'string' &&
        typeof candidate.title === 'string' &&
        isPanelType(candidate.type) &&
        Array.isArray(candidate.groupByContext) &&
        isRevivableDate(candidate.from) &&
        (candidate.to === undefined || isRevivableDate(candidate.to))
    );
}
