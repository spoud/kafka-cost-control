import { isContextKeys, toContextKeyControl, toContextKeys } from './context-keys';

describe('context key normalization', () => {
    it('wraps the select control value into the list shape', () => {
        expect(toContextKeys('application')).toEqual(['application']);
    });

    it('leaves an existing list alone', () => {
        expect(toContextKeys(['application'])).toEqual(['application']);
    });

    it('treats empty and missing as no grouping', () => {
        expect(toContextKeys('')).toEqual([]);
        expect(toContextKeys(undefined)).toEqual([]);
        expect(toContextKeys(null)).toEqual([]);
        expect(toContextKeys([])).toEqual([]);
    });

    it('unwraps back to a single control value', () => {
        expect(toContextKeyControl(['application'])).toBe('application');
        expect(toContextKeyControl('application')).toBe('application');
        expect(toContextKeyControl([])).toBe('');
        expect(toContextKeyControl(undefined)).toBe('');
    });

    it('accepts either stored shape so old panels are repaired, not discarded', () => {
        expect(isContextKeys('application')).toBe(true);
        expect(isContextKeys(['application'])).toBe(true);
        expect(isContextKeys(undefined)).toBe(false);
        expect(isContextKeys(42)).toBe(false);
    });
});
