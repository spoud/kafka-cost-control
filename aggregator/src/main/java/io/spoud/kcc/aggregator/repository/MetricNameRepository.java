package io.spoud.kcc.aggregator.repository;

import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.olap.OlapInfra;
import io.spoud.kcc.aggregator.stream.MetricReducer;
import io.spoud.kcc.olap.domain.tables.AggregatedData;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.jooq.Record1;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static io.spoud.kcc.olap.domain.Tables.AGGREGATED_DATA;
import static org.jooq.impl.DSL.max;

/**
 * Store all the metric names saw in the streams.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class MetricNameRepository {

    private final Map<String, Instant> metricNames = new ConcurrentHashMap<>(100);
    private final MetricReducer metricReducer;
    private final OlapInfra olapInfra;

    public void addMetricName(String metricName, Instant metricTime) {
        metricNames.put(metricName, metricTime);
    }

    public List<MetricNameEntity> getMetricNames() {
        return olapInfra.getDSLContext().map(dslContext -> {
            AggregatedData a = AGGREGATED_DATA.as("a");
            return dslContext
                    .selectDistinct(a.INITIAL_METRIC_NAME, max(a.END_TIME))
                    .from(a)
                    .groupBy(a.INITIAL_METRIC_NAME)
                    .stream()
                    .map(record -> new MetricNameEntity(
                            record.value1(),
                            record.value2().toInstant(),
                            metricReducer.getAggregationType(record.value1()).name()
                    ))
                    .toList();
        }).orElseGet(this::getMetricNamesFromStream);

    }

    private List<MetricNameEntity> getMetricNamesFromStream() {
        return metricNames.entrySet().stream()
                .map(entry -> new MetricNameEntity(
                        entry.getKey(),
                        entry.getValue(),
                        metricReducer.getAggregationType(entry.getKey()).name()))
                .sorted(Comparator.comparing(MetricNameEntity::metricName))
                .collect(Collectors.toList());
    }
}
