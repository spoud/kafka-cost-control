package io.spoud.kcc.aggregator.filter;

import io.quarkus.vertx.web.RouteFilter;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import io.vertx.ext.web.RoutingContext;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BasePathFilter {

    private final CostControlConfigProperties costControlConfigProperties;

    @RouteFilter(Integer.MAX_VALUE)
    void redirectBasePath(RoutingContext routingContext) {
        costControlConfigProperties.basePath()
                .filter(basePath -> basePath.length() >= 2)
                .filter(routingContext.request().uri()::startsWith)
                .ifPresentOrElse(basePath -> {
                            String newUri = routingContext.request().uri().replaceFirst(basePath, "/");
                            routingContext.reroute(newUri);
                        },
                        routingContext::next);
    }
}
