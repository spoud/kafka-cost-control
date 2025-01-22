package io.spoud.kcc.aggregator.olap;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.RestQuery;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Optional;

@Path("/olap/export")
public class DataExportResource {
    @Inject
    AggregatedMetricsRepository aggregatedMetricsRepository;

    @GET
    @Produces("text/csv")
    public Response genCsvExport(@RestQuery DateTimeParameter fromDate, @RestQuery DateTimeParameter toDate) {
        return serveExport("csv", fromDate, toDate);
    }

    @GET
    @Produces("application/jsonl")
    public Response genJsonLinesExport(@RestQuery DateTimeParameter fromDate, @RestQuery DateTimeParameter toDate) {
        return serveExport("json", fromDate, toDate);
    }

    // Note that this is just an alias for the application/jsonl endpoint. In both cases, json lines are returned.
    @GET
    @Produces("application/json")
    public Response genJsonExport(@RestQuery DateTimeParameter fromDate, @RestQuery DateTimeParameter toDate) {
        return serveExport("json", fromDate, toDate);
    }

    private Response serveExport(String format, DateTimeParameter fromDate, DateTimeParameter toDate) {
        var exportPath = aggregatedMetricsRepository.exportData(
                Optional.ofNullable(fromDate).map(DateTimeParameter::instant).orElse(null),
                Optional.ofNullable(toDate).map(DateTimeParameter::instant).orElse(null), format);
        return Optional.ofNullable(exportPath)
                .map(path -> Response.ok().entity((StreamingOutput) output -> {
                    try {
                        Files.copy(path, output);
                    } finally {
                        Log.infof("Export file %s has been downloaded and will be deleted now.", path);
                        Files.delete(path);
                    }
                }).header("Content-Disposition", "attachment; filename=export." + format).build())
                .orElse(Response.status(204).build());
    }

    public record DateTimeParameter(Instant instant) {
        public static DateTimeParameter fromString(String value) {
            try {
                return new DateTimeParameter(Instant.parse(value));
            } catch (Exception e) {
                throw new BadRequestException("Invalid date time format. Expected ISO-8601 date time in UTC time zone (e.g. 2020-01-01T00:00:00Z).");
            }
        }
    }
}
