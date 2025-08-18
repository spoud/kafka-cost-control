import { Injectable, LOCALE_ID, inject } from '@angular/core';
import {BROWSER_LOCALE} from '../app.config';

@Injectable({
    providedIn: 'root'
})
export class IntlDateService {
    private browserLocale = inject(BROWSER_LOCALE);
    private angularLocale = inject(LOCALE_ID);


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
