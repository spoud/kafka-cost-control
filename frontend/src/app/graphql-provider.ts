import { Provider } from '@angular/core';
import { Apollo, APOLLO_OPTIONS } from 'apollo-angular';
import { InMemoryCache } from '@apollo/client/core';
// import generatedFragments from '../generated/graphql/fragments';
import { AdditionalHeadersService } from './services/additional-headers.service';
import { ApolloClient, HttpLink } from '@apollo/client';

const httpLink = new HttpLink({
    uri: '/graphql',
});

function authLink(additionalHeadersService: AdditionalHeadersService): HttpLink {
    return new HttpLink({
        headers: additionalHeadersService.getHeaders(),
    });
}

export function createApollo(
    additionalHeadersService: AdditionalHeadersService
): ApolloClient.Options {
    return {
        link: authLink(additionalHeadersService).concat(httpLink),
        cache: new InMemoryCache({
            // possibleTypes: generatedFragments.possibleTypes,
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
            deps: [AdditionalHeadersService],
        },
        {
            provide: Apollo,
            useClass: Apollo,
        },
    ];
}
