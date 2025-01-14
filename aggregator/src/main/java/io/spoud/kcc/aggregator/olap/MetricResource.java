package io.spoud.kcc.aggregator.olap;

import io.quarkus.logging.Log;
import io.quarkus.runtime.configuration.ConfigUtils;
import io.smallrye.common.annotation.Blocking;
import io.spoud.kcc.data.AggregatedDataWindowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.List;
import java.util.Set;

@Produces(MediaType.APPLICATION_JSON)
@Path("/api/metrics")
@Blocking
public class MetricResource {
    @Inject
    AggregatedMetricsRepository metricRepository;

    @GET
    public Set<String> getMetrics() {
        return metricRepository.getAllMetrics();
    }

    @GET
    @Path("/{metricName}/{aggType}")
    public List<AggregatedDataWindowed> getAggregatedMetric(@RestPath String metricName,
                                                            @RestPath AggregatedMetricsRepository.AggregationType aggType,
                                                            @RestQuery AggregatedMetricsRepository.FilterSpec filter,
                                                            @RestQuery AggregatedMetricsRepository.TimestampParam startTimestamp,
                                                            @RestQuery AggregatedMetricsRepository.TimestampParam endTimestamp,
                                                            @RestQuery AggregatedMetricsRepository.GroupBySpec groupBy,
                                                            @RestQuery Integer limit,
                                                            @RestQuery Integer offset,
                                                            @RestQuery AggregatedMetricsRepository.SortSpec sort) {
        Log.infof("All params: metric=%s, aggType=%s, filter=%s, start=%s, end=%s, groupBy=%s, limit=%s, offset=%s, sort=%s",
                metricName, aggType, filter, startTimestamp, endTimestamp, groupBy, limit, offset, sort);
        return metricRepository.getAggregatedMetric(metricName,
                aggType,
                filter == null ? new AggregatedMetricsRepository.FilterSpec(List.of(), List.of()) : filter,
                startTimestamp,
                endTimestamp,
                groupBy == null ? new AggregatedMetricsRepository.GroupBySpec(List.of(), List.of(), false, false, false, false) : groupBy,
                limit != null && limit < 0 ? null : limit,
                offset != null && offset < 0 ? null : offset,
                sort);
    }

    @GET
    @Path("/tags")
    public Set<String> getTags() {
        return metricRepository.getAllTagKeys();
    }

    @GET
    @Path("/tags/{tagKey}/values")
    public Set<String> getTagValues(@RestPath String tagKey) {
        return metricRepository.getAllTagValues(tagKey);
    }

    @GET
    @Path("/contexts")
    public Set<String> getContexts() {
        return metricRepository.getAllContextKeys();
    }

    @GET
    @Path("/contexts/{contextKey}/values")
    public Set<String> getContextValues(@RestPath String contextKey) {
        return metricRepository.getAllContextValues(contextKey);
    }

    // only for testing purposes (disable in production)
    @POST
    @Path("/query")
    @Consumes(MediaType.TEXT_PLAIN)
    public String runQuery(String query) {
        if (!ConfigUtils.getProfiles().contains("test") && !ConfigUtils.getProfiles().contains("dev")) {
            Log.warn("Someone tried to run the following query in production: " + query);
            Log.warn("If this was you, remember that this endpoint is only available if QUARKUS_PROFILE=test or dev. Current profiles: " + ConfigUtils.getProfiles());
            throw new NotFoundException();
        }
        return metricRepository.runQuery(query);
    }

    @ServerExceptionMapper
    public RestResponse<ApiError> handleBadRequestException(WebApplicationException e) {
        return RestResponse.status(RestResponse.Status.BAD_REQUEST,
                new ApiError(e.getMessage(), null));
    }
}
