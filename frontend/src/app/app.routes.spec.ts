import { TestBed } from '@angular/core/testing';
import { ApolloTestingModule } from 'apollo-angular/testing';
import { routes } from './app.routes';
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
