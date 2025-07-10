package io.spoud.kcc.aggregator.olap;

import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.RestQuery;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Path("/olap/export")
public class DataExportResource {
    @Inject
    AggregatedMetricsRepository aggregatedMetricsRepository;

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @GET
    @Path("/aggregated")
    @Produces("text/csv")
    public Response aggregatedCsvExport(@RestQuery DateTimeParameter fromDate, @RestQuery DateTimeParameter toDate,
                                        @RestQuery String groupByContextKey) {
        Map<String, Collection<MetricHistoryTO>> metricToValue = aggregatedMetricsRepository.exportDataAggregated(
                Optional.ofNullable(fromDate).map(DateTimeParameter::instant).orElse(null),
                Optional.ofNullable(toDate).map(DateTimeParameter::instant).orElse(null),
                groupByContextKey);
        Map<String, List<Double>> groupByContextToValues = new TreeMap<>();
        metricToValue.forEach((s, metricHistoryTOs) -> {
            metricHistoryTOs.forEach(metricHistoryTO -> {
                groupByContextToValues.compute(
                        metricHistoryTO.getName(),
                        (k, v) -> {
                            if (v == null) {
                                v = new ArrayList<>();
                            }
                            v.add(metricHistoryTO.getValues().getFirst());
                            return v;
                        }
                );
            });
        });
        return Response.ok().entity((StreamingOutput) output -> {
                    output.write((groupByContextKey + ",").getBytes());
                    output.write(String.join(",", metricToValue.keySet()).getBytes());
                    output.write("\n".getBytes());
                    groupByContextToValues.forEach((k, v) -> {
                        Stream<String> objectStream = v.stream().map(Object::toString);
                        String line = k + "," + objectStream.collect(Collectors.joining(","));
                        try {
                            output.write(line.getBytes());
                            output.write("\n".getBytes());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }).header("Content-Disposition", "attachment; filename=export_" + nowAsIso() + ".csv")
                .build();
    }

    private String nowAsIso() {
        return LocalDateTime.now().format(formatter);
    }

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
