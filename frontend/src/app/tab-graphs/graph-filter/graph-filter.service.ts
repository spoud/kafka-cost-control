import { computed, inject, Injectable, resource, ResourceRef, Signal } from '@angular/core';
import { firstValueFrom, map } from 'rxjs';
import { MetricContextKeysGQL, MetricHistoryGQL, MetricNamesGQL } from '../../../generated/graphql/sdk';
import { MetricHistory, MetricNameEntity } from '../../../generated/graphql/types';
import { GraphFilter } from '../tab-graphs.component';

@Injectable({
    providedIn: 'root',
})
export class GraphFilterService {
    metricContextKeysGql = inject(MetricContextKeysGQL);
    metricNamesGql = inject(MetricNamesGQL);
    historyGql = inject(MetricHistoryGQL);

    contextKeysResource = resource<string[] | undefined, never>({
        loader: () =>
            firstValueFrom(
                this.metricContextKeysGql.fetch().pipe(map(res => res.data?.metricContextKeys))
            ),
    });
    metricNamesResource = resource<MetricNameEntity[] | undefined, never>({
        loader: () =>
            firstValueFrom(this.metricNamesGql.fetch().pipe(map(res => res.data?.metricNames))),
    });

    contextKeys = computed(() => this.contextKeysResource.value() || []);
    metricNames = computed(() => this.metricNamesResource.value() || []);

    historyResource(
        filter: Signal<GraphFilter | undefined>
    ): ResourceRef<MetricHistory[] | undefined> {
        return resource({
            params: () => {
                const _filter = filter();
                if (!_filter) {
                    return undefined;
                }
                return {
                    from: { instant: _filter.from },
                    to: { instant: _filter.to || new Date() },
                    metricNames: _filter.metricName || [],
                    groupByContextKeys: _filter.groupByContext || [],
                };
            },
            loader: ({ params }): Promise<MetricHistory[] | undefined> =>
                firstValueFrom(
                    this.historyGql.fetch({ variables: params }).pipe(map(res => res.data?.history))
                ),
        });
    }
}
