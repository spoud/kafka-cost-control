import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService, AuthStatus } from '../auth.service';
import { listDomains, SignInComponent } from './sign-in.component';

function render(status: AuthStatus) {
    const auth = {
        status: signal(status),
        loading: signal(false),
        error: signal<string | null>(null),
        mode: signal(status.mode),
        user: signal(status.user),
        provider: signal(status.provider ?? null),
        domains: signal(status.domains ?? []),
        signInWithProvider: vi.fn(),
        signOut: vi.fn(),
        load: vi.fn(),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
        imports: [SignInComponent],
        providers: [{ provide: AuthService, useValue: auth }],
    });
    const fixture = TestBed.createComponent(SignInComponent);
    fixture.detectChanges();
    return { el: fixture.nativeElement as HTMLElement, auth };
}

const signedOut = { authenticated: false, allowed: false, user: null };

describe('SignInComponent', () => {
    it('names the provider and the account to use', () => {
        const { el, auth } = render({
            mode: 'oidc',
            ...signedOut,
            provider: 'google',
            domains: ['spoud.io'],
        });

        expect(el.textContent).toContain('Use your spoud.io account.');
        const button = el.querySelector<HTMLButtonElement>('button.provider')!;
        expect(button.textContent).toContain('Continue with Google');
        button.click();
        expect(auth.signInWithProvider).toHaveBeenCalled();
    });

    it('falls back to single sign-on for a provider it has no name for', () => {
        const { el } = render({ mode: 'oidc', ...signedOut });

        expect(el.textContent).toContain('Use your organisation account.');
        expect(el.querySelector('button.provider')!.textContent).toContain(
            'Continue with single sign-on'
        );
    });

    it('explains a signed-in account without access and offers another one', () => {
        const { el, auth } = render({
            mode: 'oidc',
            authenticated: true,
            allowed: false,
            user: 'eve@elsewhere.org',
            domains: ['spoud.io'],
        });

        expect(el.textContent).toContain('No access');
        expect(el.textContent).toContain('eve@elsewhere.org');
        el.querySelector<HTMLButtonElement>('button')!.click();
        expect(auth.signOut).toHaveBeenCalled();
    });

    it('asks for the administrator password in basic mode', () => {
        const { el } = render({ mode: 'basic', ...signedOut });

        expect(el.querySelector('input[autocomplete="current-password"]')).not.toBeNull();
        expect(el.querySelector('button.provider')).toBeNull();
    });
});

describe('listDomains', () => {
    it('reads like a sentence', () => {
        expect(listDomains(['a.com'])).toBe('a.com');
        expect(listDomains(['a.com', 'b.com'])).toBe('a.com or b.com');
        expect(listDomains(['a.com', 'b.com', 'c.com'])).toBe('a.com, b.com or c.com');
    });
});
