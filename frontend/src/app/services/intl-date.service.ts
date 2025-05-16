import {Inject, Injectable, LOCALE_ID} from '@angular/core';
import {BROWSER_LOCALE} from '../app.config';

@Injectable({
    providedIn: 'root'
})
export class IntlDateService {

    constructor(@Inject(BROWSER_LOCALE) private browserLocale: string,
                @Inject(LOCALE_ID) private angularLocale: string) {
    }

    transform(date: Date | string, timeZone?: string): string | null {
        if (!date) {
            return null;
        }
        // format with Intl api, use browser locale with fallback to angular bundle locale
        return Intl.DateTimeFormat([this.browserLocale, this.angularLocale],
            {
                dateStyle: 'medium',
                timeStyle: 'medium',
                timeZone: timeZone
            }
        ).format(new Date(date));
    }
}
