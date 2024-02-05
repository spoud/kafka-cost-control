package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.data.ContextDataEntity;
import io.spoud.kcc.aggregator.graphql.data.ContextDataDeleteRequest;
import io.spoud.kcc.aggregator.graphql.data.ContextDataSaveRequest;
import io.spoud.kcc.aggregator.repository.ContextDataRepository;
import io.spoud.kcc.aggregator.stream.KafkaStreamManager;
import io.spoud.kcc.data.ContextData;

import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@RequiredArgsConstructor
@Authenticated
public class ContextDataResource {
    private final ContextDataRepository contextDataRepository;

    @Query("contextData")
    public @NonNull List<@NonNull ContextDataEntity> contextData() {
      return contextDataRepository.getContextObjects();
    }

    @Mutation("saveContextData")
    public @NonNull ContextDataEntity savePricingRule(ContextDataSaveRequest request) {
        return contextDataRepository.save(request.id(), request.toAvro());
    }

    @Mutation("deleteContextData")
    public ContextDataEntity deletePricingRule(ContextDataDeleteRequest request) {
        return contextDataRepository.deleteContext(request.id());
    }
}
