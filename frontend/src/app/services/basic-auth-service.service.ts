import {Injectable, Signal, signal} from '@angular/core';
import {map, Observable, Subject} from 'rxjs';
import {LoginTestGQL} from '../../generated/graphql/sdk';
import {AdditionalHeadersService} from './additional-headers.service';

export const LOCAL_STORAGE_BASIC_AUTH = 'kcc-basic-auth-hash';
export const HEADER_AUTHORIZATION = 'Authorization';

@Injectable({
    providedIn: 'root'
})
export class BasicAuthServiceService {

    private _authenticated = signal(false);

    constructor(private _additionalHeaders: AdditionalHeadersService, private _loginTest: LoginTestGQL) {
        const basicAuthHash = this.getBasicAuthHash();
        if (basicAuthHash != null) {
            // we assume that if a hash is present, the user is logged in, we do not
            this._authenticated.set(true);
            this._additionalHeaders.setHeader(HEADER_AUTHORIZATION, `Basic ${basicAuthHash}`);
        }
    }

    public authenticated(): Signal<boolean> {
        return this._authenticated.asReadonly();
    }

    private getBasicAuthHash(): string | null {
        return sessionStorage.getItem(LOCAL_STORAGE_BASIC_AUTH);
    }

    signOut(): void {
        sessionStorage.removeItem(LOCAL_STORAGE_BASIC_AUTH);
        this._additionalHeaders.removeHeader(HEADER_AUTHORIZATION);
        this._authenticated.set(false);

    }

    public signIn(username: string, password: string): Observable<string> {
        const subject = new Subject<string>();
        const basicAuth = btoa(`${username}:${password}`);
        this._additionalHeaders.setHeader(HEADER_AUTHORIZATION, `Basic ${basicAuth}`);

        this._loginTest.fetch({}).pipe(
            map((response) => {
                if (response.error) {
                    throw new Error(response.error.message);
                } else {
                    return response.data.currentUser
                }
            })
        ).subscribe({
            next: (username) => {
                // persist basic auth for next time
                sessionStorage.setItem(LOCAL_STORAGE_BASIC_AUTH, basicAuth);
                this._authenticated.set(true);
                subject.next("logged as " + username);
            }, error: (error) => {
                console.error('Login failed', error);
                sessionStorage.removeItem(LOCAL_STORAGE_BASIC_AUTH);
                subject.error(error);
            }, complete: () => {
                subject.complete();
            }
        });
        return subject;
    }
}
