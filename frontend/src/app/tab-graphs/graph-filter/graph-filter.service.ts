import {computed, inject, Injectable, resource, Signal} from '@angular/core';
import {firstValueFrom, map} from 'rxjs';
import {MetricContextKeysGQL, MetricHistoryGQL, MetricNamesGQL} from '../../../generated/graphql/sdk';
import {MetricHistory, MetricNameEntity} from '../../../generated/graphql/types';
import {GraphFilter} from '../tab-graphs.component';

@Injectable({
    providedIn: 'root'
})
export class GraphFilterService {
    metricContextKeysGql = inject(MetricContextKeysGQL);
    metricNamesGql = inject(MetricNamesGQL);
    historyGql = inject(MetricHistoryGQL);

    contextKeysResource = resource<string[], never>({
        loader: () => firstValueFrom(this.metricContextKeysGql.fetch().pipe(map(res => res.data?.metricContextKeys)))
    });
    metricNamesResource = resource<MetricNameEntity[], never>({
        loader: () => firstValueFrom(this.metricNamesGql.fetch().pipe(map(res => res.data?.metricNames)))
    });

    contextKeys = computed(() => this.contextKeysResource.value() || []);
    metricNames = computed(() => this.metricNamesResource.value() || []);

    historyResource(filter: Signal<GraphFilter | undefined>) {
        return resource({
            request: () => {
                const _filter = filter();
                if (!_filter) {
                    return undefined;
                }
                return {
                    from: _filter.from,
                    to: _filter.to || new Date(),
                    metricNames: _filter.metricName || [],
                    groupByContextKeys: _filter.groupByContext || []
                }
            },
            loader: ({request}): Promise<MetricHistory[]> => firstValueFrom(this.historyGql.fetch(request)
                .pipe(map(res => res.data?.history)))
        })
    }
}
