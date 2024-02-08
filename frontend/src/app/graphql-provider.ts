import {Provider} from '@angular/core';
import {Apollo, APOLLO_OPTIONS} from 'apollo-angular';
import {ApolloClientOptions, createHttpLink, InMemoryCache} from '@apollo/client/core';
import {setContext} from '@apollo/client/link/context';
import generatedFragments from '../generated/graphql/fragments';


const httpLink = createHttpLink({
    uri: '/graphql',
});

const authLink = setContext((_, {headers}) => {
    // get the authentication token from local storage if it exists
    const token = localStorage.getItem('token');
    // return the headers to the context so httpLink can read them
    return {
        headers: {
            ...headers,
            authorization: token ? `Basic ${token}` : '',
        }
    }
});

export function createApollo(): ApolloClientOptions<any> {
    return {
        link: authLink.concat(httpLink),
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
            deps: [],
        },
        {
            provide: Apollo,
            useClass: Apollo
        }
    ];
}
