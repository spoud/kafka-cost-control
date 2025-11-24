package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

// todo not sure...
@RegisterForReflection
public record SankeyLinksRequest (
        Instant from,
        Instant to,
        String linkFrom,
        String linkTo
) {

}
