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
                    List<String> header = metricToValue.keySet().stream()
                            .map(s -> s + " [bytes]," + s + " [%]")
                            .toList();
                    output.write(String.join(",", header).getBytes());
                    output.write("\n".getBytes());
                    List<Double> totals = extractTotals(metricToValue);
                    groupByContextToValues.forEach((k, v) -> {
                        List<String> list = new ArrayList<>();
                        for (int i = 0; i < v.size(); i++) {
                            Double value = v.get(i);
                            list.add(value.toString());
                            list.add(String.valueOf(value / totals.get(i)));
                        }
                        String line = k + "," + String.join(",", list);
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

    private List<Double> extractTotals(Map<String, Collection<MetricHistoryTO>> metricToValue) {
        List<Double> totals = new ArrayList<>();
        for (Collection<MetricHistoryTO> value : metricToValue.values()) {
            Double t = 0.0;
            for (MetricHistoryTO metricHistoryTO : value) {
                for (Double metricHistoryTOValue : metricHistoryTO.getValues()) {
                    t += metricHistoryTOValue;
                }
            }
            totals.add(t);
        }
        return totals;
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
