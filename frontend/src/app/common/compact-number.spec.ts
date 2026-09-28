import { formatCompact, formatCurrency, formatPercent } from './compact-number';

describe('formatCompact', () => {
    it('leaves values below a thousand readable', () => {
        expect(formatCompact(0)).toBe('0');
        expect(formatCompact(999)).toBe('999');
    });

    it('compacts thousands and millions', () => {
        expect(formatCompact(1000)).toMatch(/^1K$/i);
        expect(formatCompact(1234567)).toMatch(/^1\.2M$/i);
    });

    it('renders an em dash rather than NaN for missing values', () => {
        expect(formatCompact(null)).toBe('—');
        expect(formatCompact(undefined)).toBe('—');
        expect(formatCompact(Number.NaN)).toBe('—');
        expect(formatCompact(Number.POSITIVE_INFINITY)).toBe('—');
    });
});

describe('formatPercent', () => {
    it('shows one decimal', () => {
        expect(formatPercent(12.345)).toBe('12.3%');
    });

    it('renders an em dash for missing values', () => {
        expect(formatPercent(null)).toBe('—');
    });
});

describe('formatCurrency', () => {
    it('shows cents below a thousand', () => {
        expect(formatCurrency(1.5)).toBe('$1.50');
        expect(formatCurrency(0.004)).toBe('$0.00');
    });

    it('compacts thousands', () => {
        expect(formatCurrency(12345)).toMatch(/^\$12\.3K$/i);
    });

    it('renders an em dash for missing values', () => {
        expect(formatCurrency(null)).toBe('—');
        expect(formatCurrency(Number.NaN)).toBe('—');
    });
});
