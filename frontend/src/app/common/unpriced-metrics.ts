import { computed, inject, Injectable, resource } from '@angular/core';
import { firstValueFrom, map } from 'rxjs';
import { GetPricingRulesGQL, MetricNamesGQL } from '../../generated/graphql/sdk';

/** A collected metric as the views show it; the DateTime scalar arrives untyped. */
export interface CollectedMetric {
    metricName: string;
    lastSeen: string;
}

/**
 * Collected metrics that no pricing rule covers, by name. Pricing-rule costs leave them out
 * entirely, which is easy to miss when a total doesn't match the bill.
 */
export function unpricedMetrics<T extends { metricName: string }>(
    metrics: readonly T[],
    pricedMetricNames: readonly string[]
): T[] {
    const priced = new Set(pricedMetricNames);
    return metrics
        .filter(m => !priced.has(m.metricName))
        .sort((a, b) => a.metricName.localeCompare(b.metricName));
}

/** Which collected metrics have a pricing rule and which don't. */
@Injectable({ providedIn: 'root' })
export class UnpricedMetricsService {
    private metricNamesGql = inject(MetricNamesGQL);
    private pricingRulesGql = inject(GetPricingRulesGQL);

    private metricsResource = resource({
        loader: () =>
            firstValueFrom(
                this.metricNamesGql.fetch({ fetchPolicy: 'network-only' }).pipe(
                    map(res =>
                        (res.data?.metricNames ?? []).map((m): CollectedMetric => ({
                            metricName: m.metricName,
                            lastSeen: String(m.lastSeen),
                        }))
                    )
                )
            ),
    });
    private rulesResource = resource({
        loader: () =>
            firstValueFrom(
                this.pricingRulesGql
                    .fetch({ fetchPolicy: 'network-only' })
                    .pipe(map(res => res.data?.pricingRules.map(r => r.metricName) ?? []))
            ),
    });

    /** Both lists loaded without error; until then there's nothing reliable to say. */
    loaded = computed(() => this.metricsResource.hasValue() && this.rulesResource.hasValue());
    collected = computed(() => this.metricsResource.value() ?? []);
    unpriced = computed(() => unpricedMetrics(this.collected(), this.rulesResource.value() ?? []));
    pricedCount = computed(() => this.collected().length - this.unpriced().length);

    /** Rules and collected metrics change while the app is open, so views reload on entry. */
    reload(): void {
        this.metricsResource.reload();
        this.rulesResource.reload();
    }
}
