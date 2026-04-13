package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.stream.KafkaStreamManager;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;

@Path("/kafka-stream/reprocess")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@GraphQLApi
@RequiredArgsConstructor
@Authenticated
public class KafkaStreamResource {
  private final KafkaStreamManager kafkaStreamStarter;

  @POST
  @Mutation("reprocess")
  public @NonNull String reprocess(@NonNull @QueryParam("areYouSure") String areYouSure, @QueryParam("startTime") Instant startTime) {
    if (areYouSure.equals("yes")) {
      kafkaStreamStarter.reprocess(startTime);
      return "Reprocessing started, it may take a few seconds/minutes, see logs for details";
    } else {
      return "Please write 'yes' to confirm reprocessing. This will cause all the data to be reprocessed and may take a while!";
    }
  }
}
