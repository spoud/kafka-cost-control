import { Component, inject, input, viewChild } from '@angular/core';
import { BarChartComponent } from '../../../../tab-graphs/graph-panel/bar-chart/bar-chart.component';
import { PanelStore } from '../../../store/panel.store';
import { EChartsType } from 'echarts/core';
import { GraphFilterService } from '../../../../tab-graphs/graph-filter/graph-filter.service';
import { computed } from '@angular/core';
import { ChartActions } from '../../../../tab-graphs/graph-panel/chart-actions';

@Component({
    selector: 'app-area-chart',
    imports: [BarChartComponent],
    templateUrl: './line-chart-panel.component.html',
})
export class LineChartPanelComponent implements ChartActions {
    // The outlet hands the panel *this* wrapper, not the chart inside it, so the actions are
    // forwarded rather than reached through. See tab-graphs/graph-panel/chart-actions.
    private chart = viewChild.required(BarChartComponent);

    get normalized() {
        return this.chart().normalized;
    }

    get connectNulls() {
        return this.chart().connectNulls;
    }

    get showLegend() {
        return this.chart().showLegend;
    }

    exportToCsv(): void {
        this.chart().exportToCsv();
    }

    panelStore = inject(PanelStore);
    graphFilterService = inject(GraphFilterService);

    id = input.required<string>();

    filter = this.panelStore.filter(this.id);
    historyData = this.graphFilterService.historyResource(this.filter);

    // the chart spans the panel's configured window, not just the timestamps that came back
    range = computed(() => {
        const filter = this.filter();
        return filter ? { from: filter.from, to: filter.to ?? new Date() } : null;
    });

    chartInit($event: EChartsType) {
        this.panelStore.updatePanel(this.id(), { eChartsInstance: $event });
    }
}
