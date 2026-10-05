import { TestBed } from '@angular/core/testing';
import { APP_BASE_HREF } from '@angular/common';
import { AuthService, AuthStatus, SESSION_STORAGE_BASIC_AUTH } from './auth.service';
import { AdditionalHeadersService } from '../services/additional-headers.service';

const ADMIN = btoa('admin:secret');

/** A stand-in for the aggregator's /auth/me in basic mode, with password "secret". */
function basicServer() {
    return vi.fn(async (url: string, init?: { headers?: Record<string, string> }) => {
        const authorization = init?.headers?.['Authorization'];
        if (authorization && authorization !== `Basic ${ADMIN}`) {
            return new Response(null, { status: 401 });
        }
        const status: AuthStatus = authorization
            ? { mode: 'basic', authenticated: true, allowed: true, user: 'admin' }
            : { mode: 'basic', authenticated: false, allowed: false, user: null };
        return new Response(JSON.stringify(status), { status: 200 });
    });
}

async function setup(fetchMock: ReturnType<typeof vi.fn>, baseHref = '/') {
    vi.stubGlobal('fetch', fetchMock);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: APP_BASE_HREF, useValue: baseHref }] });
    const auth = TestBed.inject(AuthService);
    await vi.waitFor(() => expect(auth.loading()).toBe(false));
    return { auth, headers: TestBed.inject(AdditionalHeadersService) };
}

describe('AuthService', () => {
    beforeEach(() => sessionStorage.clear());
    afterEach(() => vi.unstubAllGlobals());

    it('asks the aggregator below the base path, marked as a script request', async () => {
        const server = basicServer();
        await setup(server, '/kcc/');

        expect(server.mock.calls[0][0]).toBe('/kcc/auth/me');
        expect(server.mock.calls[0][1]?.headers?.['X-Requested-With']).toBe('JavaScript');
    });

    it('is signed out until the password is given, then signed in', async () => {
        const { auth, headers } = await setup(basicServer());
        expect(auth.signedIn()).toBe(false);
        expect(auth.mode()).toBe('basic');

        await auth.signInWithPassword('admin', 'secret');

        expect(auth.signedIn()).toBe(true);
        expect(auth.user()).toBe('admin');
        expect(sessionStorage.getItem(SESSION_STORAGE_BASIC_AUTH)).toBe(ADMIN);
        expect(headers.getHeaders()['Authorization']).toBe(`Basic ${ADMIN}`);
    });

    it('keeps nothing of a wrong password', async () => {
        const { auth, headers } = await setup(basicServer());

        await expect(auth.signInWithPassword('admin', 'nope')).rejects.toThrow(
            'Wrong user name or password.'
        );

        expect(auth.signedIn()).toBe(false);
        expect(sessionStorage.getItem(SESSION_STORAGE_BASIC_AUTH)).toBeNull();
        expect(headers.getHeaders()['Authorization']).toBeUndefined();
    });

    it('drops a stored password that no longer works', async () => {
        sessionStorage.setItem(SESSION_STORAGE_BASIC_AUTH, btoa('admin:old'));
        const { auth } = await setup(basicServer());

        expect(auth.signedIn()).toBe(false);
        expect(auth.mode()).toBe('basic');
        expect(sessionStorage.getItem(SESSION_STORAGE_BASIC_AUTH)).toBeNull();
    });

    it('treats an expired OIDC session as signed out rather than as an error', async () => {
        const { auth } = await setup(vi.fn(async () => new Response(null, { status: 499 })));

        expect(auth.error()).toBeNull();
        expect(auth.signedIn()).toBe(false);
        expect(auth.mode()).toBe('oidc');
    });

    it('drops a stored admin password in OIDC mode instead of signing the tab in as admin', async () => {
        sessionStorage.setItem(SESSION_STORAGE_BASIC_AUTH, ADMIN);
        const oidcServer = vi.fn(
            async (url: string, init?: { headers?: Record<string, string> }) => {
                const admin = init?.headers?.['Authorization'] === `Basic ${ADMIN}`;
                const status: AuthStatus = admin
                    ? { mode: 'oidc', authenticated: true, allowed: true, user: 'admin' }
                    : { mode: 'oidc', authenticated: false, allowed: false, user: null };
                return new Response(JSON.stringify(status));
            }
        );
        const { auth, headers } = await setup(oidcServer);

        expect(auth.signedIn()).toBe(false);
        expect(auth.mode()).toBe('oidc');
        expect(sessionStorage.getItem(SESSION_STORAGE_BASIC_AUTH)).toBeNull();
        expect(headers.getHeaders()['Authorization']).toBeUndefined();
    });

    it('lets everyone in when sign-in is off', async () => {
        const open: AuthStatus = { mode: 'none', authenticated: false, allowed: true, user: null };
        const { auth } = await setup(vi.fn(async () => new Response(JSON.stringify(open))));

        expect(auth.signedIn()).toBe(true);
    });

    it('says when the aggregator is not reachable', async () => {
        const { auth } = await setup(
            vi.fn(async () => {
                throw new TypeError('Failed to fetch');
            })
        );

        expect(auth.signedIn()).toBe(false);
        expect(auth.error()).toBe('Failed to fetch');
    });
});
