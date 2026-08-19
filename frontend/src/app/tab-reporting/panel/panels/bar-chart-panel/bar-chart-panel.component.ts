import { Component, inject, input } from '@angular/core';
import { BarChartComponent } from '../../../../tab-graphs/graph-panel/bar-chart/bar-chart.component';
import { PanelStore } from '../../../store/panel.store';
import { EChartsType } from 'echarts/core';
import { GraphFilterService } from '../../../../tab-graphs/graph-filter/graph-filter.service';
import { computed } from '@angular/core';

@Component({
    imports: [BarChartComponent],
    templateUrl: './bar-chart-panel.component.html',
})
export class BarChartPanelComponent {
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
