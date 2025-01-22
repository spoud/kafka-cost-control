package io.spoud.kcc.aggregator.olap;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.RestQuery;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Path("/olap/export")
public class DataExportResource {
    @Inject
    AggregatedMetricsRepository aggregatedMetricsRepository;

    @GET
    @Produces("text/csv")
    public Response genCsvExport(@RestQuery DateTimeParameter fromDate, @RestQuery DateTimeParameter toDate) {
        var exportPath = aggregatedMetricsRepository.exportDataToCsv(
                Optional.ofNullable(fromDate).map(DateTimeParameter::localDateTime).orElse(null),
                Optional.ofNullable(toDate).map(DateTimeParameter::localDateTime).orElse(null));
        return Optional.ofNullable(exportPath)
                .map(path -> Response.ok().entity((StreamingOutput) output -> {
                    try {
                        Files.copy(path, output);
                    } finally {
                        Log.infof("Export file %s has been downloaded and will be deleted now.", path);
                        Files.delete(path);
                    }
                }).header("Content-Disposition", "attachment; filename=export.csv").build())
                .orElse(Response.status(204).build());
    }

    public record DateTimeParameter(LocalDateTime localDateTime) {
        public static DateTimeParameter fromString(String value) {
            try {
                return new DateTimeParameter(LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME));
            } catch (Exception e) {
                throw new BadRequestException("Invalid date time format. Expected ISO-8601 date time format.");
            }
        }
    }
}
