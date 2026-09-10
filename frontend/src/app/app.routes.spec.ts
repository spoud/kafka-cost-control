import { TestBed } from '@angular/core/testing';
import { ApolloTestingModule } from 'apollo-angular/testing';
import { landingPath, routes } from './app.routes';
import { BasicAuthServiceService } from './auth/basic-auth-service.service';

/**
 * The default route decides what an anonymous visitor sees first. Pointing it at a guarded page
 * turns the app's front door into the "Sign in required" error page.
 */
describe('default route', () => {
    function resolveRedirect(authenticated: boolean): string {
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({ imports: [ApolloTestingModule] });
        const auth = TestBed.inject(BasicAuthServiceService);
        if (authenticated) {
            (auth as unknown as { _authenticated: { set(v: boolean): void } })._authenticated.set(
                true
            );
        }
        const empty = routes.find(r => r.path === '');
        const redirect = empty?.redirectTo as () => string;
        return TestBed.runInInjectionContext(() => redirect());
    }

    it('sends an anonymous visitor to a public page, not the sign-in wall', () => {
        expect(resolveRedirect(false)).toBe('/explore');
    });

    it('still sends a signed-in user to Cost Overview', () => {
        expect(resolveRedirect(true)).toBe('/costs');
    });

    it('applies the same rule to unknown URLs', () => {
        const wildcard = routes.find(r => r.path === '**');
        expect(typeof wildcard?.redirectTo).toBe('function');
    });
});

/**
 * Anything that sends a user somewhere by default shares this rule, so a guarded page can never
 * become the destination for someone who cannot open it. The shell's logo uses it too.
 */
describe('landingPath', () => {
    it('sends an anonymous visitor to a public page', () => {
        expect(landingPath(false)).toBe('/explore');
    });

    it('sends a signed-in user to Cost Overview', () => {
        expect(landingPath(true)).toBe('/costs');
    });
});

/**
 * /graphs was renamed to /explore and /home was removed. Both would otherwise fall through to
 * the wildcard, which resolves per-user — sending a signed-in visitor following an old /graphs
 * link to Cost Overview instead of the page that link meant.
 */
describe('legacy routes', () => {
    it('sends /graphs to Explore, whoever is asking', () => {
        const graphs = routes.find(r => r.path === 'graphs');
        expect(graphs?.redirectTo).toBe('explore');
    });

    it('declares the legacy redirects before the catch-alls', () => {
        const indexOf = (path: string) => routes.findIndex(r => r.path === path);

        expect(indexOf('graphs')).toBeLessThan(indexOf(''));
        expect(indexOf('home')).toBeLessThan(indexOf(''));
        expect(indexOf('')).toBeLessThan(indexOf('**'));
    });
});
