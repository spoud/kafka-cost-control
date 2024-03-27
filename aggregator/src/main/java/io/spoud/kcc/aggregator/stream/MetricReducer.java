package io.spoud.kcc.aggregator.stream;

import io.spoud.kcc.data.AggregatedData;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AllArgsConstructor;
import org.apache.kafka.streams.kstream.Reducer;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Reducer that combines two {@link AggregatedData} instances into one. To be used inside a Kafka Streams application.
 * The aggregation type is determined by the configuration property {@code cc.metrics.aggregations.<metricName>}.
 * If the property is not set, the default aggregation type is {@link AggregationType#SUM}, which combines the
 * AggregatedData instances by summing their values.
 */
@ApplicationScoped
public class MetricReducer implements Reducer<AggregatedData> {
    private static final String AGGREGATION_TYPE_CONFIG_TEMPLATE = "cc.metrics.aggregations.%s";

    @Override
    public AggregatedData apply(AggregatedData left, AggregatedData right) {
        if (!Objects.equals(left.getInitialMetricName(), right.getInitialMetricName())) {
            throw new IllegalArgumentException("""
                    MetricReducer can only be applied to data with the same metric name,
                    but got left metric name: %s and right metric name: %s"""
                    .formatted(left.getInitialMetricName(), right.getInitialMetricName()));
        }
        var metricName = left.getInitialMetricName();
        var aggregationType = ConfigProvider.getConfig()
                .getOptionalValue(AGGREGATION_TYPE_CONFIG_TEMPLATE.formatted(metricName), String.class)
                .map(String::toUpperCase)
                .map(type -> {
                    try {
                        return AggregationType.valueOf(type);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("""
                                Invalid aggregation type '%s' for metric '%s'. Allowed values are: %s"""
                                .formatted(type, metricName, AggregationType.values()));
                    }
                })
                .orElse(AggregationType.SUM);
        double combined = aggregationType.combine(left.getValue(), right.getValue());
        right.setValue(combined);
        return right;
    }

    @AllArgsConstructor
    public enum AggregationType {
        SUM(Double::sum),
        MAX(Math::max);

        public double combine(double left, double right) {
            return combiner.apply(left, right);
        }

        private final BiFunction<Double, Double, Double> combiner;
    }
}
