import { Component, input, viewChild } from '@angular/core';
import { MatCard, MatCardContent } from '@angular/material/card';
import { MatIcon } from '@angular/material/icon';
import { MatIconButton } from '@angular/material/button';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import { BarChartComponent } from './bar-chart/bar-chart.component';
import { PieChartComponent } from './pie-chart/pie-chart.component';
import { MetricHistory } from '../../../generated/graphql/types';
import { DateRange } from '../../common/date-range-quick-select/date-range-quick-select.component';

@Component({
    selector: 'app-graph-panel',
    imports: [
        MatCard,
        MatCardContent,
        MatIcon,
        MatIconButton,
        MatMenu,
        MatMenuItem,
        MatMenuTrigger,
        BarChartComponent,
        PieChartComponent,
    ],
    templateUrl: './graph-panel.component.html',
    styleUrl: './graph-panel.component.scss',
})
export class GraphPanelComponent {
    metricsData = input.required<MetricHistory[]>();

    range = input<DateRange | null>(null);

    // The charts are static in this template, so unlike a Reporting panel these are direct view
    // queries - no ngComponentOutlet indirection. The card owns the menu so it can sit on the
    // title's line instead of on a row of its own below it.
    protected history = viewChild.required(BarChartComponent);
    protected share = viewChild.required(PieChartComponent);
}
