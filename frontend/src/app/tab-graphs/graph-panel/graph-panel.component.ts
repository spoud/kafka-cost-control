import { Component, input } from '@angular/core';
import { MatDivider } from '@angular/material/divider';
import { BarChartComponent } from './bar-chart/bar-chart.component';
import { PieChartComponent } from './pie-chart/pie-chart.component';
import {MetricHistory} from '../../../generated/graphql/sdk';

@Component({
    selector: 'app-graph-panel',
    imports: [MatDivider, BarChartComponent, PieChartComponent],
    templateUrl: './graph-panel.component.html',
})
export class GraphPanelComponent {
    metricsData = input.required<MetricHistory[]>();
}
