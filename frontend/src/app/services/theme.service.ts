import { computed, DOCUMENT, effect, inject, Injectable, signal } from '@angular/core';
import { CHART_THEME_DARK, CHART_THEME_LIGHT, registerChartThemes } from './chart-theme';
import { readRaw, writeRaw } from '../common/persisted-state';

const THEME_MODE_KEY = 'theme-mode';
const DARK_MEDIA_QUERY = '(prefers-color-scheme: dark)';

/** The OS preference, used only to seed the initial theme. Absent under jsdom. */
function prefersDark(): boolean {
    return window.matchMedia?.(DARK_MEDIA_QUERY).matches ?? false;
}

/**
 * The stored theme, falling back to the OS preference. Any other stored value — including the
 * 'system' written by an earlier build — counts as unset.
 */
function loadIsDark(): boolean {
    const stored = readRaw(THEME_MODE_KEY);
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
        writeRaw(THEME_MODE_KEY, dark ? 'dark' : 'light');
    }
}
