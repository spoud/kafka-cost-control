import { costRows, monthStatuses } from './cost.component';

const costs = {
    metrics: [
        {
            metric: 'confluent_kafka_server_request_bytes',
            shares: [
                { name: 'tenant=a', contextValues: ['a'], price: 300, estimatedPrice: 0 },
                { name: 'tenant=b', contextValues: ['b'], price: 100, estimatedPrice: 40 },
            ],
        },
    ],
    months: [
        {
            month: '2026-09',
            billed: true,
            from: '2026-09-20T00:00:00Z',
            to: '2026-10-01T00:00:00Z',
            billedUntil: '2026-10-01T00:00:00Z',
        },
        {
            month: '2026-10',
            billed: true,
            from: '2026-10-01T00:00:00Z',
            to: '2026-10-10T00:00:00Z',
            billedUntil: '2026-10-06T00:00:00Z',
        },
        {
            month: '2026-11',
            billed: false,
            from: '2026-11-01T00:00:00Z',
            to: '2026-11-03T00:00:00Z',
            billedUntil: null,
        },
    ],
};

describe('cost overview', () => {
    it('turns shares into dollar rows with their share of the cost', () => {
        expect(costRows(costs)).toEqual([
            {
                metric: 'confluent_kafka_server_request_bytes',
                context: ['a'],
                total: 3,
                estimated: 0,
                percentage: 0.75,
            },
            {
                metric: 'confluent_kafka_server_request_bytes',
                context: ['b'],
                total: 1,
                estimated: 0.4,
                percentage: 0.25,
            },
        ]);
    });

    it('says which months are billed, billed in part, or estimated', () => {
        const months = monthStatuses(costs);

        expect(months.map(m => [m.billed, !!m.billedUpTo])).toEqual([
            [true, false],
            [true, true], // the bill stops on the 6th, before the range's part of October ends
            [false, false],
        ]);
        expect(months[1].billedUpTo?.getDate()).toBe(5);
    });
});
