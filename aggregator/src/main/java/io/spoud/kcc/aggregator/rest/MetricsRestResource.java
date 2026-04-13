package io.spoud.kcc.aggregator.rest;

import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.olap.DataExportResource.DateTimeParameter;
import io.spoud.kcc.aggregator.service.MetricsService;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Path("/metrics")
@Produces(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
public class MetricsRestResource {
    private final MetricsService metricsService;

    @GET
    @Path("/history")
    public List<MetricHistoryTO> history(
            @QueryParam("metricNames") Set<String> metricNames,
            @QueryParam("groupByContextKeys") Set<String> groupByContextKeys,
            @QueryParam("from") DateTimeParameter from,
            @QueryParam("to") DateTimeParameter to) {
        
        Instant fromInstant = Optional.ofNullable(from).map(DateTimeParameter::instant).orElseThrow(() -> new BadRequestException("from parameter is required"));
        Instant toInstant = Optional.ofNullable(to).map(DateTimeParameter::instant).orElse(null);

        return metricsService.getHistory(metricNames, groupByContextKeys, fromInstant, toInstant);
    }

    @GET
    @Path("/names")
    @PermitAll
    public List<MetricNameEntity> metricNames() {
        return metricsService.getMetricNames();
    }

    @GET
    @Path("/context-keys")
    @PermitAll
    public List<String> contextKeys() {
        return metricsService.getContextKeys();
    }
}
