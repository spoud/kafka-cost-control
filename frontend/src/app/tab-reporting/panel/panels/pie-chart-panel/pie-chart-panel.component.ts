import { Component, inject, input, viewChild } from '@angular/core';
import { PieChartComponent } from '../../../../tab-graphs/graph-panel/pie-chart/pie-chart.component';
import { PanelStore } from '../../../store/panel.store';
import { EChartsType } from 'echarts/core';
import { GraphFilterService } from '../../../../tab-graphs/graph-filter/graph-filter.service';
import { ChartActions } from '../../../../tab-graphs/graph-panel/chart-actions';

@Component({
    imports: [PieChartComponent],
    templateUrl: './pie-chart-panel.component.html',
})
export class PieChartPanelComponent implements ChartActions {
    // The outlet hands the panel *this* wrapper, not the chart inside it, so the actions are
    // forwarded rather than reached through. No normalized mode on a pie.
    private chart = viewChild.required(PieChartComponent);

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

    chartInit($event: EChartsType) {
        this.panelStore.updatePanel(this.id(), { eChartsInstance: $event });
    }
}
