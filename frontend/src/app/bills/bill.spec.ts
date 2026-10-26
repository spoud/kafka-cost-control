import { Allocation } from '../../generated/graphql/types';
import {
    billLabel,
    billTotal,
    describeLine,
    coveredUntilFromLastDay,
    lastDayFromCoveredUntil,
    monthLastDay,
    ratesFrom,
    recentMonths,
} from './bill';

describe('bill helpers', () => {
    it("starts a bill's rates on the day it ends", () => {
        expect(ratesFrom({ month: '2026-10', coveredUntil: null })).toBe(
            '2026-11-01T00:00:00.000Z'
        );
        expect(ratesFrom({ month: '2026-12', coveredUntil: null })).toBe(
            '2027-01-01T00:00:00.000Z'
        );
        expect(ratesFrom({ month: '2026-10', coveredUntil: '2026-10-06T00:00:00Z' })).toBe(
            '2026-10-06T00:00:00.000Z'
        );
        // a bill ending within a day: the day it ends in, whose earlier hours are the bill's
        expect(ratesFrom({ month: '2026-10', coveredUntil: '2026-10-06T12:00:00Z' })).toBe(
            '2026-10-06T00:00:00.000Z'
        );
    });

    it('adds every line; an estimated one adds nothing', () => {
        expect(billTotal([{ amount: 1 }, { amount: null }, { amount: 3 }, { amount: -1.5 }])).toBe(
            2.5
        );
    });

    it('describes a line with how it is shared', () => {
        expect(
            describeLine({
                description: 'Network',
                amount: 0.49,
                allocation: Allocation.Metrics,
                metrics: ['bytes_in', 'bytes_out'],
            })
        ).toBe('Network $0.49 → bytes_in, bytes_out');
        expect(
            describeLine({
                description: 'Partitions',
                amount: null,
                allocation: Allocation.Metrics,
                metrics: ['kafka_topic_partition_count'],
            })
        ).toBe('Partitions estimated → kafka_topic_partition_count');
        expect(
            describeLine({
                description: 'Connect',
                amount: 12,
                allocation: Allocation.Context,
                context: [{ key: 'application', value: 'etl' }],
            })
        ).toBe('Connect $12.00 → application=etl');
        expect(
            describeLine({ description: 'Support', amount: 5, allocation: Allocation.Usage })
        ).toBe('Support $5.00 → spread by usage');
    });

    it("names a cluster's bill after its month and cluster", () => {
        expect(billLabel({ month: '2026-10', cluster: 'poc-cluster' })).toMatch(
            /2026, poc-cluster$/
        );
        expect(billLabel({ month: '2026-10', cluster: null })).toMatch(/2026$/);
    });

    it('turns the last day a month-to-date bill includes into where it stops, and back', () => {
        const lastDay = new Date(2026, 9, 5); // 5 October, local
        const coveredUntil = coveredUntilFromLastDay(lastDay);

        expect(coveredUntil).toBe('2026-10-06T00:00:00.000Z');
        const back = lastDayFromCoveredUntil(coveredUntil);
        expect([back.getFullYear(), back.getMonth(), back.getDate()]).toEqual([2026, 9, 5]);
    });

    it('lists recent months newest first, across a year boundary', () => {
        expect(recentMonths(new Date(Date.UTC(2026, 1, 15)), 3)).toEqual([
            '2026-02',
            '2026-01',
            '2025-12',
        ]);
    });

    it('knows the last day of a month', () => {
        expect(monthLastDay('2026-02').toISOString()).toBe('2026-02-28T00:00:00.000Z');
    });
});
