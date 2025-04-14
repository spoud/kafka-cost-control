import {computed, inject, Injectable, resource} from '@angular/core';
import {firstValueFrom, map} from 'rxjs';
import {MetricContextKeysGQL, MetricNamesGQL} from '../../../generated/graphql/sdk';
import {MetricNameEntity} from '../../../generated/graphql/types';

@Injectable({
    providedIn: 'root'
})
export class GraphFilterService {
    metricContextKeysGql = inject(MetricContextKeysGQL);
    metricNamesGql = inject(MetricNamesGQL);

    contextKeysResource = resource<string[], never>({
        loader: () => firstValueFrom(this.metricContextKeysGql.fetch().pipe(map(res => res.data?.metricContextKeys)))
    });
    metricNamesResource = resource<MetricNameEntity[], never>({
        loader: () => firstValueFrom(this.metricNamesGql.fetch().pipe(map(res => res.data?.metricNames)))
    });

    contextKeys = computed(() => this.contextKeysResource.value() || []);
    metricNames = computed(() => this.metricNamesResource.value() || []);
}
