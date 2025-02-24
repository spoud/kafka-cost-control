import {IntlDatePipe} from './intl-date.pipe';

const pipe = new IntlDatePipe('de-CH', 'en-US');

describe('IntlDatePipe', () => {
    it('create an instance', () => {
        expect(pipe).toBeTruthy();
    });

    it('should format date correctly', () => {
        // we want to test UTC times ('Z' suffix), to have stable times (CET / CEST) we always use CET here
        expect(pipe.transform('2020-03-04T12:34:56Z', 'CET')).toBe('04.03.2020, 13:34:56');
    });

    it('should handle milliseconds', () => {
        expect(pipe.transform('2025-02-21T08:36:51.999Z', 'CET')).toBe('21.02.2025, 09:36:51');
    });

    it('should accept Date types', () => {
        expect(pipe.transform(new Date('2025-02-21T08:36:51.999Z'), 'CET')).toBe('21.02.2025, 09:36:51');
    });
});
