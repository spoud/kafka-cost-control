import { MetricHistory } from '../../../generated/graphql/types';
import { plotted } from './plotted';

const series = {
    name: 'tenant=a',
    times: ['2026-10-01T00:00:00Z'],
    values: [1024],
    costs: [0.15],
    context: [],
} as unknown as MetricHistory;

describe('plotted', () => {
    it('plots the usage by default and the cost in cost mode', () => {
        expect(plotted([series], false)[0].values).toEqual([1024]);
        expect(plotted([series], true)[0].values).toEqual([0.15]);
        // the response itself stays untouched, so switching back needs no new request
        expect(series.values).toEqual([1024]);
    });
});
