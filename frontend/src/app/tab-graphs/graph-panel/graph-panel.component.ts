import { Component, input } from '@angular/core';
import { MetricHistory } from '../../../generated/graphql/types';
import { MatDivider } from '@angular/material/divider';
import { BarChartComponent } from './bar-chart/bar-chart.component';
import { PieChartComponent } from './pie-chart/pie-chart.component';

@Component({
    selector: 'app-graph-panel',
    imports: [MatDivider, BarChartComponent, PieChartComponent],
    templateUrl: './graph-panel.component.html',
})
export class GraphPanelComponent {
    metricsData = input.required<MetricHistory[]>();
}
