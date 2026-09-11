import { stripInlineMarkdown } from './plain-text';

describe('stripInlineMarkdown', () => {
    it('unwraps bold and code spans the model emits', () => {
        expect(stripInlineMarkdown('There are **35 applications** in `aggregated_data`.')).toBe(
            'There are 35 applications in aggregated_data.'
        );
        expect(stripInlineMarkdown('__2.04 TB__ retained')).toBe('2.04 TB retained');
    });

    it('leaves unpaired and bare markers alone', () => {
        // a lone asterisk is arithmetic or a footnote, not emphasis
        expect(stripInlineMarkdown('cost = rate * hours')).toBe('cost = rate * hours');
        expect(stripInlineMarkdown('a ** b')).toBe('a ** b');
    });

    it('does not reach across lines', () => {
        // otherwise two unrelated lines each holding one marker would be joined
        expect(stripInlineMarkdown('**start\nend**')).toBe('**start\nend**');
    });

    it('leaves block structure intact for pre-wrap to render', () => {
        const table = '| Topic | Retained |\n|---|---|\n| orders | 655 GB |';

        expect(stripInlineMarkdown(table)).toBe(table);
    });

    it('handles empty input', () => {
        expect(stripInlineMarkdown('')).toBe('');
    });
});
