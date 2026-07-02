import { LOCALE_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { IntlDatePipe } from './intl-date.pipe';
import { BROWSER_LOCALE } from '../app.config';

describe('IntlDatePipe', () => {
    let pipe: IntlDatePipe;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                IntlDatePipe,
                { provide: BROWSER_LOCALE, useValue: 'de-CH' },
                { provide: LOCALE_ID, useValue: 'en-US' },
            ],
        });
        pipe = TestBed.inject(IntlDatePipe);
    });

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
        expect(pipe.transform(new Date('2025-02-21T08:36:51.999Z'), 'CET')).toBe(
            '21.02.2025, 09:36:51'
        );
    });
});