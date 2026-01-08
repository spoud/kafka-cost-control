import { Provider } from '@angular/core';
import { Apollo, APOLLO_OPTIONS } from 'apollo-angular';
import {
    ApolloClientOptions,
    ApolloLink,
    createHttpLink,
    InMemoryCache,
} from '@apollo/client/core';
import { setContext } from '@apollo/client/link/context';
import generatedFragments from '../generated/graphql/fragments';
import { AdditionalHeadersService } from './services/additional-headers.service';
import { APP_BASE_HREF } from '@angular/common';

function authLink(additionalHeadersService: AdditionalHeadersService): ApolloLink {
    return setContext((_, { headers }) => {
        return {
            headers: {
                ...headers,
                ...additionalHeadersService.getHeaders(),
            },
        };
    });
}

export function createApollo(
    additionalHeadersService: AdditionalHeadersService,
    baseHref: string
): ApolloClientOptions<unknown> {
    const contextPath = baseHref || '/';
    const cleanPath = contextPath.endsWith('/') ? contextPath : `${contextPath}/`;

    return {
        link: authLink(additionalHeadersService).concat(
            createHttpLink({
                uri: `${cleanPath}graphql`,
            })
        ),
        cache: new InMemoryCache({
            possibleTypes: generatedFragments.possibleTypes,
        }),
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
