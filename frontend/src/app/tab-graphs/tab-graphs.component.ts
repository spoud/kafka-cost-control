import { Component, computed, inject, signal } from '@angular/core';
import { GraphFilterComponent } from './graph-filter/graph-filter.component';
import { GraphPanelComponent } from './graph-panel/graph-panel.component';
import { GraphFilterService } from './graph-filter/graph-filter.service';
import { MatIcon } from '@angular/material/icon';
import { MatDivider } from '@angular/material/divider';

export interface GraphFilter {
    from: Date;
    to?: Date;
    metricName?: string;
    groupByContext: string[];
}

@Component({
    selector: 'app-tab-graphs',
    imports: [GraphFilterComponent, GraphPanelComponent, MatIcon, MatDivider],
    templateUrl: './tab-graphs.component.html',
    styleUrl: './tab-graphs.component.scss',
})
export class TabGraphsComponent {
    graphFilterService = inject(GraphFilterService);

    filter = signal<GraphFilter | undefined>(undefined);

    csvDownloadUrl = computed(() => {
        const from =
            this.filter()?.from.toISOString() ??
            new Date(new Date().getTime() - 7 * 24 * 60 * 60 * 1000).toISOString();
        const to = this.filter()?.to?.toISOString() ?? new Date().toISOString();
        return `/olap/export/aggregated?fromDate=${from}&toDate=${to}&groupByContextKey=${this.filter()?.groupByContext}`;
    });

    historyData = this.graphFilterService.historyResource(this.filter);
}
