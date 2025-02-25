import {ApplicationConfig, InjectionToken} from '@angular/core';
import {provideRouter} from '@angular/router';

import {routes} from './app.routes';
import {provideAnimations} from '@angular/platform-browser/animations';
import {provideHttpClient} from '@angular/common/http';
import {provideGraphql} from './graphql-provider';
import {provideNativeDateAdapter} from '@angular/material/core';

export const BROWSER_LOCALE = new InjectionToken<string>('BrowserLocale')

export const appConfig: ApplicationConfig = {
    providers: [
        provideRouter(routes),
        provideAnimations(),
        provideHttpClient(),
        provideGraphql(),
        provideNativeDateAdapter(),
        {
            provide: BROWSER_LOCALE,
            useValue: Intl.DateTimeFormat().resolvedOptions().locale
        }
    ],
};
