import { CollectedMetric, unpricedMetrics } from './unpriced-metrics';

const metric = (metricName: string): CollectedMetric => ({
    metricName,
    lastSeen: '2026-09-29T14:00:00Z',
});

describe('unpricedMetrics', () => {
    it('keeps only the collected metrics that no rule covers, sorted by name', () => {
        const collected = [
            metric('sent_bytes'),
            metric('request_bytes'),
            metric('partition_count'),
        ];
        expect(unpricedMetrics(collected, ['request_bytes']).map(m => m.metricName)).toEqual([
            'partition_count',
            'sent_bytes',
        ]);
    });

    it('ignores rules for metrics that were never collected', () => {
        expect(unpricedMetrics([metric('request_bytes')], ['request_bytes', 'old_metric'])).toEqual(
            []
        );
    });

    it('reports every metric when there are no rules', () => {
        expect(unpricedMetrics([metric('b'), metric('a')], []).map(m => m.metricName)).toEqual([
            'a',
            'b',
        ]);
    });
});
