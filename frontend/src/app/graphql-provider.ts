import { Provider } from '@angular/core';
import { Apollo, APOLLO_OPTIONS } from 'apollo-angular';
import { InMemoryCache } from '@apollo/client/core';
import { AdditionalHeadersService } from './services/additional-headers.service';
import { ApolloClient, HttpLink } from '@apollo/client';
import { APP_BASE_HREF } from '@angular/common';

function authLink(additionalHeadersService: AdditionalHeadersService): HttpLink {
    return new HttpLink({
        headers: additionalHeadersService.getHeaders(),
    });
}

export function createApollo(
    additionalHeadersService: AdditionalHeadersService,
    baseHref: string
): ApolloClient.Options {
    const contextPath = baseHref || '/';
    const cleanPath = contextPath.endsWith('/') ? contextPath : `${contextPath}/`;
    return {
        link: authLink(additionalHeadersService).concat(
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
            deps: [AdditionalHeadersService, APP_BASE_HREF],
        },
        {
            provide: Apollo,
            useClass: Apollo,
        },
    ];
}
