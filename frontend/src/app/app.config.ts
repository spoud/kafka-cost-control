import { ApplicationConfig, InjectionToken } from '@angular/core';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { provideHttpClient } from '@angular/common/http';
import { provideGraphql } from './graphql-provider';
import { provideNativeDateAdapter } from '@angular/material/core';
import { APP_BASE_HREF, PlatformLocation } from '@angular/common';

export const BROWSER_LOCALE = new InjectionToken<string>('BrowserLocale');

export const appConfig: ApplicationConfig = {
    providers: [
        provideRouter(routes),
        provideHttpClient(),
        {
            provide: APP_BASE_HREF,
            useFactory: (s: PlatformLocation) => s.getBaseHrefFromDOM(),
            deps: [PlatformLocation],
        },
        provideGraphql(),
        provideNativeDateAdapter(),
        {
            provide: BROWSER_LOCALE,
            useValue: Intl.DateTimeFormat().resolvedOptions().locale,
        },
    ],
};
