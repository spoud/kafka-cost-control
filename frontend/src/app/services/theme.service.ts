import { computed, DOCUMENT, effect, inject, Injectable, signal } from '@angular/core';

export type ThemeMode = 'light' | 'system' | 'dark';

@Injectable({
    providedIn: 'root',
})
export class ThemeService {
    private document = inject(DOCUMENT);
    private readonly THEME_MODE_KEY = 'theme-mode';
    private readonly DARK_MEDIA_QUERY = '(prefers-color-scheme: dark)';
    private readonly systemDark = signal<boolean>(window.matchMedia(this.DARK_MEDIA_QUERY).matches);

    themeMode = signal<ThemeMode>(this.loadThemeMode());

    readonly isDark = computed(() => {
        const mode = this.themeMode();
        return mode === 'dark' || (mode === 'system' && this.systemDark());
    });

    constructor() {
        const prefersColorSchemeDark = window.matchMedia(this.DARK_MEDIA_QUERY);
        prefersColorSchemeDark.addEventListener('change', e => this.systemDark.set(e.matches));

        effect(() => {
            this.document.documentElement.classList.toggle('dark', this.isDark());
        });
    }

    setThemeMode(mode: ThemeMode): void {
        this.themeMode.set(mode);
        localStorage.setItem(this.THEME_MODE_KEY, mode);
    }

    private loadThemeMode(): ThemeMode {
        const stored = localStorage.getItem(this.THEME_MODE_KEY);
        if (stored === 'light' || stored === 'dark' || stored === 'system') return stored;
        return 'system';
    }
}
