package io.spoud.kcc.aggregator.graphql;

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

@Path("/api/v1/kafka-stream/reprocess")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@GraphQLApi
@RequiredArgsConstructor
public class KafkaStreamResource {
  private final KafkaStreamManager kafkaStreamStarter;

  @POST
  @Mutation("reprocess")
  public @NonNull String reprocess(@NonNull @QueryParam("areYouSure") String areYouSure, @QueryParam("startTime") Instant startTime) {
    if (areYouSure.equals("yes")) {
      return kafkaStreamStarter.reprocess(startTime);
    } else {
      return "Please write 'yes' to confirm reprocessing. Stored data from the start time on is deleted and rebuilt from the raw topics with today's context and pricing rules, which may take a while!";
    }
  }
}
