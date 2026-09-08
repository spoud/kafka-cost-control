import { computed, DOCUMENT, effect, inject, Injectable, signal } from '@angular/core';
import { CHART_THEME_DARK, CHART_THEME_LIGHT, registerChartThemes } from './chart-theme';

const THEME_MODE_KEY = 'theme-mode';
const DARK_MEDIA_QUERY = '(prefers-color-scheme: dark)';

/**
 * The OS preference, used only to pick a starting theme. Optional-chained because jsdom has no
 * matchMedia, and a service that reads it during construction would otherwise fail to inject.
 */
function prefersDark(): boolean {
    return window.matchMedia?.(DARK_MEDIA_QUERY).matches ?? false;
}

/**
 * Light or dark, and nothing else — the two states the UI can actually set. An earlier version
 * also modelled 'system', but no control could select it, so once a user touched the toggle it
 * became unreachable. The OS preference now seeds the first visit rather than being followed
 * live.
 *
 * A 'system' left in storage by that build reads as "not set" here, falls back to the OS
 * preference, and so behaves exactly as it did before — which is why the key and its values are
 * deliberately unchanged.
 *
 * Guarded because localStorage throws outright when a browser blocks site data, and this runs
 * during construction of a root-provided service, i.e. at app bootstrap.
 */
function loadIsDark(): boolean {
    let stored: string | null = null;
    try {
        stored = localStorage.getItem(THEME_MODE_KEY);
    } catch {
        // storage blocked entirely; the OS preference is a fine answer
    }
    if (stored === 'dark' || stored === 'light') {
        return stored === 'dark';
    }
    return prefersDark();
}

@Injectable({
    providedIn: 'root',
})
export class ThemeService {
    private document = inject(DOCUMENT);

    readonly isDark = signal<boolean>(loadIsDark());

    // Name of the registered ECharts theme (see chart-theme.ts) matching the current mode, so
    // chart components can bind `[theme]="themeService.chartTheme()"` instead of each picking
    // their own colors based on isDark().
    readonly chartTheme = computed(() => (this.isDark() ? CHART_THEME_DARK : CHART_THEME_LIGHT));

    constructor() {
        registerChartThemes();

        effect(() => {
            this.document.documentElement.classList.toggle('dark', this.isDark());
        });
    }

    setDark(dark: boolean): void {
        this.isDark.set(dark);
        try {
            localStorage.setItem(THEME_MODE_KEY, dark ? 'dark' : 'light');
        } catch {
            // the theme still applies for this session, it just will not be remembered
        }
    }
}
