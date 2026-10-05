import { computed, inject, Injectable, signal } from '@angular/core';
import { APP_BASE_HREF } from '@angular/common';
import { AdditionalHeadersService } from '../services/additional-headers.service';

export type AuthMode = 'none' | 'basic' | 'oidc';

/** What the aggregator's open /auth/me says about this browser. */
export interface AuthStatus {
    mode: AuthMode;
    authenticated: boolean;
    /** Signed in and let in; always true in "none" mode. */
    allowed: boolean;
    user: string | null;
}

// sessionStorage, not localStorage: this holds the user's base64 basic-auth credentials, and
// scoping them to the tab means they do not survive a browser restart. Do not "fix" this to
// localStorage - the key name says session for that reason.
export const SESSION_STORAGE_BASIC_AUTH = 'kcc-basic-auth-hash';
export const HEADER_AUTHORIZATION = 'Authorization';
// Quarkus OIDC answers requests marked like this with 499 instead of redirecting them to the
// identity provider, which a fetch can't follow; the UI then shows its sign-in page.
export const HEADER_REQUESTED_WITH = 'X-Requested-With';

/**
 * Who is using the app. The whole UI waits for this: it is either signed in (or open, in "none"
 * mode) or it shows the sign-in page, never a half-working app.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
    private readonly headers = inject(AdditionalHeadersService);
    private readonly baseHref = withSlash(inject(APP_BASE_HREF, { optional: true }) ?? '/');

    private readonly _status = signal<AuthStatus | null>(null);
    private readonly _error = signal<string | null>(null);

    readonly status = this._status.asReadonly();
    /** Why /auth/me could not be read, e.g. the aggregator is down. */
    readonly error = this._error.asReadonly();
    readonly loading = computed(() => this._status() === null && this._error() === null);
    readonly signedIn = computed(() => this._status()?.allowed === true);
    readonly mode = computed(() => this._status()?.mode ?? null);
    readonly user = computed(() => this._status()?.user ?? null);

    constructor() {
        this.headers.setHeader(HEADER_REQUESTED_WITH, 'JavaScript');
        const basicAuthHash = sessionStorage.getItem(SESSION_STORAGE_BASIC_AUTH);
        if (basicAuthHash != null) {
            this.headers.setHeader(HEADER_AUTHORIZATION, `Basic ${basicAuthHash}`);
        }
        void this.load();
    }

    async load(): Promise<AuthStatus | null> {
        this._error.set(null);
        try {
            const response = await fetch(`${this.baseHref}auth/me`, {
                headers: this.headers.getHeaders(),
            });
            if (response.status === 401 || response.status === 499) {
                // A wrong or changed password: ask again without it.
                if (this.headers.getHeaders()[HEADER_AUTHORIZATION]) {
                    this.forgetPassword();
                    return this.load();
                }
                // An expired OIDC session (499) is refused even here; signed out, nothing worse.
                const status: AuthStatus = {
                    mode: response.status === 499 ? 'oidc' : 'basic',
                    authenticated: false,
                    allowed: false,
                    user: null,
                };
                this._status.set(status);
                return status;
            }
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            const status = (await response.json()) as AuthStatus;
            this._status.set(status);
            return status;
        } catch (e) {
            this._status.set(null);
            this._error.set(e instanceof Error ? e.message : String(e));
            return null;
        }
    }

    /** Basic mode. Rejects with a message for the user when the credentials don't get in. */
    async signInWithPassword(username: string, password: string): Promise<void> {
        const hash = btoa(`${username}:${password}`);
        this.headers.setHeader(HEADER_AUTHORIZATION, `Basic ${hash}`);
        const status = await this.load();
        if (status?.allowed) {
            sessionStorage.setItem(SESSION_STORAGE_BASIC_AUTH, hash);
            return;
        }
        this.forgetPassword();
        throw new Error(
            this._error() ? 'Kafka Cost Control is not reachable.' : 'Wrong user name or password.'
        );
    }

    /** OIDC mode: off to the identity provider, back to this page afterwards. */
    signInWithProvider(): void {
        const here = window.location.pathname + window.location.search;
        window.location.assign(`${this.baseHref}auth/login?redirect=${encodeURIComponent(here)}`);
    }

    signOut(): void {
        if (this.mode() === 'oidc') {
            window.location.assign(
                `${this.baseHref}auth/logout?redirect=${encodeURIComponent(this.baseHref)}`
            );
            return;
        }
        this.forgetPassword();
        void this.load();
    }

    /** A request was refused: the session ended, or the password changed. */
    sessionExpired(): void {
        const status = this._status();
        if (status && status.mode !== 'none' && status.allowed) {
            void this.load();
        }
    }

    private forgetPassword(): void {
        sessionStorage.removeItem(SESSION_STORAGE_BASIC_AUTH);
        this.headers.removeHeader(HEADER_AUTHORIZATION);
    }
}

function withSlash(path: string): string {
    return path.endsWith('/') ? path : `${path}/`;
}
