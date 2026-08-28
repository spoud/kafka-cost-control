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

/**
 * What a click on a legend entry meant. Plain click isolates that series (and a second click on
 * an already-isolated series brings everything back); shift-click hides just that one, which is
 * the faster gesture when a single noisy series is in the way.
 */
export interface LegendClick {
    name: string;
    additive: boolean;
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

    toggleSeries = output<LegendClick>();

    onClick(event: MouseEvent, name: string): void {
        this.toggleSeries.emit({ name, additive: event.shiftKey });
    }
}
