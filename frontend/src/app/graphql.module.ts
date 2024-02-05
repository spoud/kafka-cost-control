import generatedFragments from '../generated/graphql/fragments';
import {NgModule} from '@angular/core';
import {ApolloClientOptions, createHttpLink, InMemoryCache} from '@apollo/client/core';
import {APOLLO_OPTIONS, ApolloModule} from 'apollo-angular';
import {setContext} from '@apollo/client/link/context';

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

@NgModule({
  imports: [
    ApolloModule
  ],
  providers: [
    {
      provide: APOLLO_OPTIONS,
      useFactory: createApollo,
      deps: [],
    },
  ],
})
export class GraphQLModule {
  constructor() {

  }
}
