import { Provider } from '@angular/core';
import { Apollo, APOLLO_OPTIONS } from 'apollo-angular';
import { InMemoryCache } from '@apollo/client/core';
import { AdditionalHeadersService } from './services/additional-headers.service';
import { ApolloClient, HttpLink, ServerError } from '@apollo/client';
import { ErrorLink } from '@apollo/client/link/error';
import { SetContextLink } from '@apollo/client/link/context';
import { APP_BASE_HREF } from '@angular/common';
import { AuthService } from './auth/auth.service';

// Read per request, so a sign-in or sign-out applies to the next one. (This was an HttpLink, which
// ends the chain: the HttpLink after it with the base-path URI never ran.)
function authLink(additionalHeadersService: AdditionalHeadersService): SetContextLink {
    return new SetContextLink(prevContext => ({
        headers: { ...prevContext['headers'], ...additionalHeadersService.getHeaders() },
    }));
}

/** A refused request means the session ended (or the password changed): back to the sign-in. */
function signInAgainWhenRefused(auth: AuthService): ErrorLink {
    return new ErrorLink(({ error }) => {
        if (ServerError.is(error) && (error.statusCode === 401 || error.statusCode === 499)) {
            auth.sessionExpired();
        }
    });
}

export function createApollo(
    additionalHeadersService: AdditionalHeadersService,
    baseHref: string,
    auth: AuthService
): ApolloClient.Options {
    const contextPath = baseHref || '/';
    const cleanPath = contextPath.endsWith('/') ? contextPath : `${contextPath}/`;
    return {
        link: signInAgainWhenRefused(auth)
            .concat(authLink(additionalHeadersService))
            .concat(
                new HttpLink({
                    uri: `${cleanPath}graphql`,
                })
            ),
        cache: new InMemoryCache(),
        defaultOptions: {
            watchQuery: {
                errorPolicy: 'all',
            },
            query: { fetchPolicy: 'network-only' },
        },
    };
}

export function provideGraphql(): Provider[] {
    return [
        {
            provide: APOLLO_OPTIONS,
            useFactory: createApollo,
            deps: [AdditionalHeadersService, APP_BASE_HREF, AuthService],
        },
        {
            provide: Apollo,
            useClass: Apollo,
        },
    ];
}
