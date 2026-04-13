package io.spoud.kcc.aggregator.graphql;

import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.olap.DataExportResource.DateTimeParameter;
import io.spoud.kcc.aggregator.service.MetricsService;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Path("/metrics")
@Produces(MediaType.APPLICATION_JSON)
@GraphQLApi
@RequiredArgsConstructor
public class MetricsResource {
    private final MetricsService metricsService;

    @GET
    @Path("/history")
    @Query("history")
    public @NonNull List<@NonNull MetricHistoryTO> history(
            @NonNull @QueryParam("metricNames") Set<String> metricNames,
            @NonNull @QueryParam("groupByContextKeys") Set<String> groupByContextKeys,
            @NonNull @QueryParam("from") DateTimeParameter from,
            @QueryParam("to") DateTimeParameter to) {
        
        Instant fromInstant = Optional.ofNullable(from).map(DateTimeParameter::instant).orElseThrow(() -> new BadRequestException("from parameter is required"));
        Instant toInstant = Optional.ofNullable(to).map(DateTimeParameter::instant).orElse(null);

        return metricsService.getHistory(metricNames, groupByContextKeys, fromInstant, toInstant);
    }

    @GET
    @Path("/names")
    @PermitAll
    @Query("metricNames")
    public @NonNull List<@NonNull MetricNameEntity> metricNames() {
        return metricsService.getMetricNames();
    }

    @GET
    @Path("/context-keys")
    @PermitAll
    @Query("metricContextKeys")
    public @NonNull List<@NonNull String> contextKeys() {
        return metricsService.getContextKeys();
    }
}
