import {Component, inject, resource, signal} from '@angular/core';
import {GraphFilterComponent} from './graph-filter/graph-filter.component';
import {GraphPanelComponent} from './graph-panel/graph-panel.component';
import {MetricHistoryGQL} from '../../generated/graphql/sdk';
import {firstValueFrom, map} from 'rxjs';
import {MetricHistory} from '../../generated/graphql/types';


export interface GraphFilter {
    from: Date;
    to?: Date;
    metricName?: string;
    groupByContext: string[];
}

@Component({
    selector: 'app-tab-graphs',
    imports: [
        GraphFilterComponent,
        GraphPanelComponent,
    ],
    templateUrl: './tab-graphs.component.html',
    styleUrl: './tab-graphs.component.scss'
})
export class TabGraphsComponent {

    historyGql = inject(MetricHistoryGQL);

    filter = signal<GraphFilter | undefined>(undefined);

    historyData = resource({
        request: () => {
            if (!this.filter()?.from) {
                // From is the minimum required
                return undefined;
            }
            return {
                from: this.filter()?.from,
                to: this.filter()?.to || new Date(),
                metricNames: this.filter()?.metricName || [],
                groupByContextKeys: this.filter()?.groupByContext || []
            }
        }
        ,
        loader: ({request}): Promise<MetricHistory[]> => firstValueFrom(this.historyGql.fetch(request)
            .pipe(map(res => res.data?.history)))

    });

}
