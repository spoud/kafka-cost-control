import {Provider} from '@angular/core';
import {Apollo, APOLLO_OPTIONS} from 'apollo-angular';
import {ApolloClientOptions, ApolloLink, createHttpLink, InMemoryCache} from '@apollo/client/core';
import {setContext} from '@apollo/client/link/context';
import generatedFragments from '../generated/graphql/fragments';
import {AdditionalHeadersService} from './services/additional-headers.service';


const httpLink = createHttpLink({
    uri: '/graphql',
});

function authLink(additionalHeadersService: AdditionalHeadersService): ApolloLink {
    return setContext((_, {headers}) => {
        return {
            headers: {
                ...headers,
                ...additionalHeadersService.getHeaders(),
            }
        }
    });
}

export function createApollo(additionalHeadersService: AdditionalHeadersService): ApolloClientOptions<unknown> {
    return {
        link: authLink(additionalHeadersService).concat(httpLink),
        cache: new InMemoryCache({
            possibleTypes: generatedFragments.possibleTypes,
        }),
        defaultOptions: {
            watchQuery: {
                errorPolicy: 'all'
            },
            query: {fetchPolicy: 'network-only'},
        },
    };
}

export function provideGraphql(): Provider[] {
    return [

        {
            provide: APOLLO_OPTIONS,
            useFactory: createApollo,
            deps: [
                AdditionalHeadersService
            ],
        },
        {
            provide: Apollo,
            useClass: Apollo
        }
    ];
}
