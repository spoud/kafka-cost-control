import { endOfDay, isSameDay, startOfDay } from './date-range';

describe('date range helpers', () => {
    it('ends the day at its last millisecond', () => {
        // the date picker yields midnight, and the range is read inclusively, so a `to` left at
        // midnight excluded the whole selected day - picking one day as both ends returned nothing
        const end = endOfDay(new Date('2026-08-20T00:00:00'));

        expect(end.getHours()).toBe(23);
        expect(end.getMinutes()).toBe(59);
        expect(end.getSeconds()).toBe(59);
        expect(end.getMilliseconds()).toBe(999);
    });

    it('spans a whole day when both ends are the same date', () => {
        const day = new Date('2026-08-20T09:30:00');

        expect(endOfDay(day).getTime() - startOfDay(day).getTime()).toBe(24 * 60 * 60 * 1000 - 1);
    });

    it('does not mutate its argument', () => {
        const original = new Date('2026-08-20T09:30:00');
        const before = original.getTime();

        endOfDay(original);
        startOfDay(original);

        expect(original.getTime()).toBe(before);
    });

    it('is idempotent, so applying it to an already-ended range changes nothing', () => {
        const once = endOfDay(new Date('2026-08-20T00:00:00'));

        expect(endOfDay(once).getTime()).toBe(once.getTime());
    });

    it('compares calendar days, not instants', () => {
        expect(isSameDay(new Date('2026-08-20T00:00:00'), new Date('2026-08-20T23:59:59'))).toBe(
            true
        );
        expect(isSameDay(new Date('2026-08-20T23:59:59'), new Date('2026-08-21T00:00:00'))).toBe(
            false
        );
    });
});
