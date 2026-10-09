import { Allocation } from '../../generated/graphql/types';
import {
    billTotal,
    describeOtherLine,
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

    it('adds every line, the other lines included', () => {
        expect(
            billTotal({
                networkWrite: 1,
                networkRead: 2,
                storage: null,
                partitions: 3,
                otherLines: [{ amount: 1 }, { amount: -1.5 }],
            })
        ).toBe(5.5);
    });

    it('describes an other line with where it goes', () => {
        expect(
            describeOtherLine({
                description: 'Connect',
                amount: 12,
                allocation: Allocation.Context,
                context: [{ key: 'application', value: 'etl' }],
            })
        ).toBe('Connect $12.00 → application=etl');
        expect(
            describeOtherLine({ description: 'Support', amount: 5, allocation: Allocation.Usage })
        ).toBe('Support $5.00 → spread by usage');
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
