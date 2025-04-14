import {Component, computed, inject, input, resource, Signal} from '@angular/core';
import {MetricHistoryGQL} from '../../../../../generated/graphql/sdk';
import {GraphFilter} from '../../../../tab-graphs/tab-graphs.component';
import {MetricHistory} from '../../../../../generated/graphql/types';
import {firstValueFrom, map} from 'rxjs';
import {BarChartComponent} from '../../../../tab-graphs/graph-panel/bar-chart/bar-chart.component';
import {PanelStore} from '../../../store/panel.store';
import {GraphPanelComponent} from '../../../../tab-graphs/graph-panel/graph-panel.component';
import {EChartsType} from 'echarts/core';

@Component({
    imports: [
        GraphPanelComponent, // TODO: we require provider provideEchartsCore({echarts}) from here...
        BarChartComponent
    ],
    templateUrl: './bar-chart-panel.component.html',
})
export class BarChartPanel {

    panelStore = inject(PanelStore);
    historyGql = inject(MetricHistoryGQL);

    id = input.required<string>();

    type = input.required<BarOrLine>();

    filter: Signal<GraphFilter> = computed(() => {
        const panel = this.panelStore.entityMap()[this.id()];
        return {
            from: panel.from,
            to: panel.to,
            metricName: panel.metricName,
            groupByContext: panel.groupByContext
        };
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
    })

    chartInit($event: EChartsType) {
        this.panelStore.updatePanel(this.id(), {eChartsInstance: $event});
    }
}

export type BarOrLine = 'Bar' | 'Line';
