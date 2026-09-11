import { readPersisted, writePersisted, isUnknownArray, isRevivableDate } from './persisted-state';

const KEY = 'test_persisted_state';
const isNumberArray = (value: unknown): value is number[] =>
    Array.isArray(value) && value.every(v => typeof v === 'number');

describe('persisted state', () => {
    beforeEach(() => localStorage.removeItem(KEY));
    afterEach(() => localStorage.removeItem(KEY));

    it('round-trips a value', () => {
        writePersisted(KEY, 1, [1, 2, 3]);

        expect(readPersisted(KEY, 1, isNumberArray)).toEqual([1, 2, 3]);
    });

    it('returns null and clears the key when the JSON is unreadable', () => {
        localStorage.setItem(KEY, '{{{');

        expect(readPersisted(KEY, 1, isNumberArray)).toBeNull();
        expect(localStorage.getItem(KEY)).toBeNull();
    });

    it('discards a value written by an older version', () => {
        writePersisted(KEY, 1, [1, 2, 3]);

        expect(readPersisted(KEY, 2, isNumberArray)).toBeNull();
        expect(localStorage.getItem(KEY)).toBeNull();
    });

    it('discards a value that no longer matches the expected shape', () => {
        writePersisted(KEY, 1, ['not', 'numbers']);

        expect(readPersisted(KEY, 1, isNumberArray)).toBeNull();
    });

    it('adopts an unversioned value that still matches the shape', () => {
        // written before the envelope existed - migrated rather than thrown away
        localStorage.setItem(KEY, JSON.stringify([1, 2, 3]));

        expect(readPersisted(KEY, 1, isNumberArray)).toEqual([1, 2, 3]);
    });

    it('discards an unversioned value that does not match the shape', () => {
        localStorage.setItem(KEY, JSON.stringify({ nope: true }));

        expect(readPersisted(KEY, 1, isNumberArray)).toBeNull();
    });

    it('recognises revivable dates', () => {
        expect(isRevivableDate('2026-01-01T00:00:00Z')).toBe(true);
        expect(isRevivableDate(new Date())).toBe(true);
        expect(isRevivableDate('not a date')).toBe(false);
        expect(isRevivableDate(undefined)).toBe(false);
        expect(isRevivableDate(null)).toBe(false);
    });

    it('isUnknownArray only accepts arrays', () => {
        expect(isUnknownArray([])).toBe(true);
        expect(isUnknownArray({})).toBe(false);
        expect(isUnknownArray(null)).toBe(false);
    });
});
