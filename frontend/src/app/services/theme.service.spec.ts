import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

const THEME_MODE_KEY = 'theme-mode';

/**
 * ThemeService is root-provided and seeds itself from localStorage during construction, so
 * anything thrown here happens at app bootstrap — there is no route to fall back to, the page
 * simply does not come up. Browsers set to block site data throw on the access itself, not just
 * on a bad value, which is the case these cover.
 */
describe('ThemeService', () => {
    const realGetItem = Storage.prototype.getItem;
    const realSetItem = Storage.prototype.setItem;

    beforeEach(() => {
        localStorage.removeItem(THEME_MODE_KEY);
        TestBed.resetTestingModule();
    });

    afterEach(() => {
        Storage.prototype.getItem = realGetItem;
        Storage.prototype.setItem = realSetItem;
        localStorage.removeItem(THEME_MODE_KEY);
    });

    it('reads a stored choice', () => {
        localStorage.setItem(THEME_MODE_KEY, 'dark');

        expect(TestBed.inject(ThemeService).isDark()).toBe(true);
    });

    it('treats a "system" left by an older build as unset rather than as a theme', () => {
        // that build's third state is gone; falling through to the OS preference is what it did
        localStorage.setItem(THEME_MODE_KEY, 'system');

        expect(TestBed.inject(ThemeService).isDark()).toBe(false);
    });

    it('persists a choice under the same key and values as before', () => {
        const service = TestBed.inject(ThemeService);

        service.setDark(true);

        expect(service.isDark()).toBe(true);
        expect(localStorage.getItem(THEME_MODE_KEY)).toBe('dark');
    });

    it('still constructs when reading storage throws', () => {
        Storage.prototype.getItem = () => {
            throw new Error('site data blocked');
        };

        expect(() => TestBed.inject(ThemeService)).not.toThrow();
    });

    it('still applies the theme when writing storage throws', () => {
        Storage.prototype.setItem = () => {
            throw new Error('site data blocked');
        };
        const service = TestBed.inject(ThemeService);

        expect(() => service.setDark(true)).not.toThrow();
        // the choice holds for this session, it just is not remembered
        expect(service.isDark()).toBe(true);
    });
});
