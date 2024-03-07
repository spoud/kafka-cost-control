package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.stream.KafkaStreamManager;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;

@GraphQLApi
@RequiredArgsConstructor
@Authenticated
public class KafkaStreamResource {
  private final KafkaStreamManager kafkaStreamStarter;

  @Mutation("reprocess")
  public @NonNull String reprocess(@NonNull String areYouSure, Instant startTime) {
    if (areYouSure.equals("yes")) {
      kafkaStreamStarter.reprocess(startTime);
      return "Reprocessing started, it may take a few seconds/minutes, see logs for details";
    } else {
      return "Please write 'yes' to confirm reprocessing. This will cause all the data to be reprocessed and may take a while!";
    }
  }
}
