import {Component, computed, inject, input, resource} from '@angular/core';
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
    });

    chartInit($event: EChartsType) {
        this.panelStore.updatePanel(this.id(), {eChartsInstance: $event});
    }
}
