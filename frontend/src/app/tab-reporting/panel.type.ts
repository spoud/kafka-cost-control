import {BarChartPanel} from './panel/panels/bar-chart-panel/bar-chart-panel.component';
import {PieChartPanelComponent} from './panel/panels/pie-chart-panel/pie-chart-panel.component';
import {EChartsType} from 'echarts/core';
import {LineChartPanelComponent} from './panel/panels/line-chart-panel/line-chart-panel.component';

export type Panel = {
    id: string;
    title: string;
    description?: string;
    type: 'StackedBar' | 'Line' | 'Pie';

    // graph filter
    from: Date,
    to?: Date,
    metricName?: string;
    groupByContext: string[];

    eChartsInstance?: EChartsType

    // display, currently unused
    rows?: number;
    columns?: number;
}

export const TYPE_TO_COMPONENT_MAPPING = {
    'StackedBar': BarChartPanel,
    'Line': LineChartPanelComponent,
    'Pie': PieChartPanelComponent,
}
