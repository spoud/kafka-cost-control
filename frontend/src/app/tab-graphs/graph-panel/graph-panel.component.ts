import { Component, input } from '@angular/core';
import { MatCard, MatCardContent, MatCardHeader, MatCardTitle } from '@angular/material/card';
import { MatIcon } from '@angular/material/icon';
import { BarChartComponent } from './bar-chart/bar-chart.component';
import { PieChartComponent } from './pie-chart/pie-chart.component';
import { MetricHistory } from '../../../generated/graphql/types';
import { DateRange } from '../../common/date-range-quick-select/date-range-quick-select.component';

@Component({
    selector: 'app-graph-panel',
    imports: [
        MatCard,
        MatCardContent,
        MatCardHeader,
        MatCardTitle,
        MatIcon,
        BarChartComponent,
        PieChartComponent,
    ],
    templateUrl: './graph-panel.component.html',
    styleUrl: './graph-panel.component.scss',
})
export class GraphPanelComponent {
    metricsData = input.required<MetricHistory[]>();

    range = input<DateRange | null>(null);
}
