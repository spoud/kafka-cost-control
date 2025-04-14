import {Component, computed, inject, input, resource} from '@angular/core';
import {GraphFilter} from '../../../../tab-graphs/tab-graphs.component';
import {MetricHistory} from '../../../../../generated/graphql/types';
import {firstValueFrom, map} from 'rxjs';
import {MetricHistoryGQL} from '../../../../../generated/graphql/sdk';
import {PieChartComponent} from '../../../../tab-graphs/graph-panel/pie-chart/pie-chart.component';
import {PanelStore} from '../../../store/panel.store';
import {EChartsType} from 'echarts/core';

@Component({
    imports: [
        PieChartComponent
    ],
    templateUrl: './pie-chart-panel.component.html',
})
export class PieChartPanelComponent {

    panelStore = inject(PanelStore);
    historyGql = inject(MetricHistoryGQL);

    id = input.required<string>();

    filter = computed(() => {
        return this.panelStore.entities().filter(panel => panel.id === this.id())
            .map(panel => (<GraphFilter>{
                from: panel.from,
                to: panel.to,
                metricName: panel.metricName,
                groupByContext: panel.groupByContext
            }))[0];
    });

    historyData = resource({
        request: () => {
            return {
                from: this.filter()?.from,
                to: this.filter()?.to || new Date(),
                metricNames: this.filter()?.metricName || [],
                groupByContextKeys: this.filter()?.groupByContext || []
            }
        },
        loader: ({request}): Promise<MetricHistory[]> => firstValueFrom(this.historyGql.fetch(request)
            .pipe(map(res => res.data?.history)))
    });

    chartInit($event: EChartsType) {
        this.panelStore.updatePanel(this.id(), {eChartsInstance: $event});
    }
}
