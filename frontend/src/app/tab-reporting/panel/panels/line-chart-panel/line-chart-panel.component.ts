import {Component, inject, input} from '@angular/core';
import {BarChartComponent} from '../../../../tab-graphs/graph-panel/bar-chart/bar-chart.component';
import {PanelStore} from '../../../store/panel.store';
import {EChartsType} from 'echarts/core';
import {GraphFilterService} from '../../../../tab-graphs/graph-filter/graph-filter.service';
import {GraphPanelComponent} from '../../../../tab-graphs/graph-panel/graph-panel.component';

@Component({
    selector: 'app-area-chart',
    imports: [
        GraphPanelComponent, // TODO: we require provider provideEchartsCore({echarts}) from here...
        BarChartComponent
    ],
    templateUrl: './line-chart-panel.component.html',
})
export class LineChartPanelComponent {

    panelStore = inject(PanelStore);
    graphFilterService = inject(GraphFilterService);

    id = input.required<string>();

    filter = this.panelStore.filter(this.id);
    historyData = this.graphFilterService.historyResource(this.filter);


    chartInit($event: EChartsType) {
        this.panelStore.updatePanel(this.id(), {eChartsInstance: $event});
    }
}
