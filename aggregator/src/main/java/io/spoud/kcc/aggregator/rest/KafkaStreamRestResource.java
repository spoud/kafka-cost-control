package io.spoud.kcc.aggregator.rest;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.olap.DataExportResource.DateTimeParameter;
import io.spoud.kcc.aggregator.stream.KafkaStreamManager;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;

@Path("/kafka-stream")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
@Authenticated
public class KafkaStreamRestResource {
    private final KafkaStreamManager kafkaStreamStarter;

    @POST
    @Path("/reprocess")
    public String reprocess(@QueryParam("areYouSure") String areYouSure, @QueryParam("startTime") DateTimeParameter startTime) {
        if ("yes".equals(areYouSure)) {
            Instant start = Optional.ofNullable(startTime).map(DateTimeParameter::instant).orElse(null);
            kafkaStreamStarter.reprocess(start);
            return "Reprocessing started, it may take a few seconds/minutes, see logs for details";
        } else {
            return "Please write 'yes' to confirm reprocessing. This will cause all the data to be reprocessed and may take a while!";
        }
    }
}
