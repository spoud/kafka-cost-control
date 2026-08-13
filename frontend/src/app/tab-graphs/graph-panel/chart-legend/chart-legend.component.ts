import { Component, input, output } from '@angular/core';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatSelect } from '@angular/material/select';
import { MatOption } from '@angular/material/core';

export type LegendSort = 'name' | 'value';

export interface ChartLegendItem {
    name: string;
    color: string;
    selected: boolean;
}

@Component({
    selector: 'app-chart-legend',
    imports: [MatFormField, MatLabel, MatSelect, MatOption],
    templateUrl: './chart-legend.component.html',
    styleUrl: './chart-legend.component.scss',
})
export class ChartLegendComponent {
    items = input.required<ChartLegendItem[]>();

    sort = input<LegendSort>('value');

    sortChange = output<LegendSort>();

    toggleSeries = output<string>();
}
