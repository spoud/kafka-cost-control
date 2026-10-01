import { toCents } from './cost.component';

describe('toCents', () => {
    it('rounds to whole cents, which the API requires', () => {
        expect(toCents(0.3311)).toBe(33);
        expect(toCents(0.29)).toBe(29); // 0.29 * 100 is 28.999999999999996
        expect(toCents(12.345)).toBe(1235);
    });

    it('treats an empty field as 0', () => {
        expect(toCents(null)).toBe(0);
        expect(toCents(undefined)).toBe(0);
    });
});
