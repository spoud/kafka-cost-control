import {Component, inject, input} from '@angular/core';
import {PieChartComponent} from '../../../../tab-graphs/graph-panel/pie-chart/pie-chart.component';
import {PanelStore} from '../../../store/panel.store';
import {EChartsType} from 'echarts/core';
import {GraphFilterService} from '../../../../tab-graphs/graph-filter/graph-filter.service';

@Component({
    imports: [
        PieChartComponent
    ],
    templateUrl: './pie-chart-panel.component.html',
})
export class PieChartPanelComponent {

    panelStore = inject(PanelStore);
    graphFilterService = inject(GraphFilterService);

    id = input.required<string>();

    filter = this.panelStore.filter(this.id);
    historyData = this.graphFilterService.historyResource(this.filter);

    chartInit($event: EChartsType) {
        this.panelStore.updatePanel(this.id(), {eChartsInstance: $event});
    }
}
