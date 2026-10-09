import { MetricHistory } from '../../../generated/graphql/types';

/**
 * The charts read `values`. In cost mode that is each bucket's cost from the costs view (the
 * bill's share where a bill covers the hour, the pricing rule's elsewhere); the history response
 * carries both, so switching needs no new request.
 */
export function plotted(series: MetricHistory[], showCost: boolean): MetricHistory[] {
    return showCost ? series.map(s => ({ ...s, values: s.costs })) : series;
}
