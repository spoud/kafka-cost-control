package io.spoud.kcc.aggregator.graphql;

import io.quarkus.security.Authenticated;
import io.spoud.kcc.aggregator.graphql.data.CalculateTopDownRequest;
import io.spoud.kcc.aggregator.graphql.data.CalculateTopDownResponse;
import io.spoud.kcc.aggregator.graphql.data.TableResponse;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

import java.util.Map;
import java.util.function.Function;

@GraphQLApi
@RequiredArgsConstructor
public class CostsResource {

    private final AggregatedMetricsRepository aggregatedMetricsRepository;

    Map<String, Function<CalculateTopDownRequest, Integer>> metricToProvidedValue = Map.of(
            "confluent_kafka_server_retained_bytes", CalculateTopDownRequest::kafkaStorageCents,
            "confluent_kafka_server_request_bytes", CalculateTopDownRequest::kafkaNetworkReadCents,
            "confluent_kafka_server_response_bytes", CalculateTopDownRequest::kafkaNetworkWriteCents
    );


    @Authenticated
    @Query("calculateTable")
    public @NonNull TableResponse calculateTable(CalculateTopDownRequest request) {
        return aggregatedMetricsRepository.calculateTable(request);
    }

    /**
     * End price (e.g. from confluent) to a distribution (according to consumption percentage)
     */
    @Authenticated
    @Query("calculateTopDown")
    public @NonNull CalculateTopDownResponse calculateCosts(CalculateTopDownRequest request) {
        return aggregatedMetricsRepository.calculateCosts(request);
        /*
        List<CalculateTopDownResponse.MetricToDistributionMap> metricToDistributionMapList = new ArrayList<>();

        metricToProvidedValue.forEach((metricName, value) -> {
            Integer priceInCents = value.apply(request);
            if (priceInCents == 0) {
                // if we have a zero amount of costs we don't do any calculations for that metric
                return;
            }
            List<MetricEO> history = aggregatedMetricsRepository.getHistory(request.from(),
                    request.to(),
                    Set.of(metricName));
            Map<String, Double> summedForEach = history.stream()
                    .collect(Collectors.groupingBy(MetricEO::name, Collectors.summingDouble(MetricEO::value)));
            double total = summedForEach.values().stream().mapToDouble(Double::doubleValue).sum();
            Map<String, Double> percentages = summedForEach.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue() / total
                    ));
            List<CalculateTopDownResponse.MetricToDistributionMap.NameToPrice> nameToPrice = percentages.entrySet().stream()
                    .filter(entry -> entry.getValue() * priceInCents > CUTOFF)
                    .map(entry -> {
                        return new CalculateTopDownResponse.MetricToDistributionMap.NameToPrice(
                                entry.getKey(),
                                entry.getValue() * priceInCents
                        );
                    })
                    .toList();
            metricToDistributionMapList.add(new CalculateTopDownResponse.MetricToDistributionMap(metricName, nameToPrice));
        });
        return new CalculateTopDownResponse(metricToDistributionMapList);

         */
    }
}
