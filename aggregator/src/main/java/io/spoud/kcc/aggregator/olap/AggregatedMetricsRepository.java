package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.olap.domain.tables.AggregatedData;
import io.spoud.kcc.olap.domain.tables.records.AggregatedDataRecord;
import io.vertx.core.impl.ConcurrentHashSet;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.impl.DSL;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static io.spoud.kcc.olap.domain.Tables.AGGREGATED_DATA;

@Startup
@ApplicationScoped
public class AggregatedMetricsRepository {
    public static final TypeReference<Map<String, String>> MAP_STRING_STRING_TYPE_REF = new TypeReference<>() {
    };

    private final OlapConfigProperties olapConfig;
    private final OlapInfra olapInfra;
    private final BlockingQueue<AggregatedDataWindowed> rowBuffer;
    private final MetricNameRepository metricNameRepository;
    private final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Set<String> contextKeys = new ConcurrentHashSet<>();

    public AggregatedMetricsRepository(OlapConfigProperties olapConfig, OlapInfra olapInfra, MetricNameRepository metricNameRepository) {
        Log.info("Initializing AggregatedMetricsRepository");
        var startTime = Instant.now();
        this.olapConfig = olapConfig;
        this.rowBuffer = new ArrayBlockingQueue<>(olapConfig.databaseMaxBufferedRows());
        this.olapInfra = olapInfra;
        this.metricNameRepository = metricNameRepository;

        Log.info("Precomputing context keys");
        contextKeys.addAll(getAllJsonKeys("context"));

        Log.infof("AggregatedMetricsRepository initialized after %s", Duration.between(startTime, Instant.now()));
    }

    private static void ensureIdentifierIsSafe(String identifier) {
        if (!identifier.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid identifier. Expected only letters, numbers, and underscores");
        }
    }

    @Shutdown
    public void cleanUp() {
        if (olapConfig.enabled()) {
            Log.info("Shutting down OLAP module. Performing final flush and closing connection...");
            flushToDb();
            olapInfra.getConnection().ifPresent((conn) -> {
                try {
                    conn.close();
                    Log.info("Closed OLAP database connection");
                } catch (SQLException e) {
                    Log.error("Failed to close OLAP database connection", e);
                }
            });
        }
    }

    @Scheduled(every = "${cc.olap.database.flush-interval.seconds}s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    synchronized void flushToDb() {
        olapInfra.getConnection().ifPresent((conn) -> {
            // Drain the buffer. This flush will deal only with the current buffer elements, not with any new rows added while this flush is running
            // This prevents the potential edge-case where the buffer is emptied and filled at the same rate, causing the flush to never finish.
            // Note that since adding to the buffer happens from the stream processing thread, this carries a slight risk of slowing down the stream processing.
            var finalRowBuffer = new ArrayDeque<AggregatedDataWindowed>(rowBuffer.size());
            rowBuffer.drainTo(finalRowBuffer);
            var skipped = 0;
            var count = 0;
            var startTime = Instant.now();
            var addedContextKeys = new HashSet<String>();
            try (var stmt = conn.prepareStatement("INSERT OR REPLACE INTO aggregated_data VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (var metric = finalRowBuffer.poll(); metric != null; metric = finalRowBuffer.poll()) {
                    Log.debugv("Ingesting metric: {0}", metric);
                    var start = metric.getStartTime();
                    var end = metric.getEndTime();
                    var tags = "";
                    var context = "";
                    try {
                        tags = OBJECT_MAPPER.writeValueAsString(metric.getTags());
                        context = OBJECT_MAPPER.writeValueAsString(metric.getContext());
                    } catch (JsonProcessingException e) {
                        Log.warn("Failed to serialize tags or context. Skipping metric...", e);
                        skipped++;
                        continue;
                    }
                    addedContextKeys.addAll(metric.getContext().keySet());
                    var target = metric.getContext().getOrDefault("topic", "unknown"); // for now, the only possible target is the topic
                    var id = DigestUtils.sha1Hex(String.valueOf(start) +
                            end +
                            metric.getInitialMetricName() +
                            metric.getEntityType().name() +
                            metric.getName() +
                            tags +
                            context +
                            target);
                    stmt.setObject(1, start.atOffset(ZoneOffset.UTC));
                    stmt.setObject(2, end.atOffset(ZoneOffset.UTC));
                    stmt.setString(3, metric.getInitialMetricName());
                    stmt.setString(4, metric.getEntityType().name());
                    stmt.setString(5, metric.getName());
                    stmt.setString(6, tags);
                    stmt.setString(7, context);
                    stmt.setDouble(8, metric.getValue());
                    stmt.setString(9, target);
                    stmt.setString(10, id);
                    stmt.addBatch();
                    count++;
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                Log.error("Failed to ingest ALL metrics to OLAP database", e);
                return;
            }
            contextKeys.addAll(addedContextKeys); // this is only safe to do here, once the flush is complete
            if (count != 0 || skipped != 0) {
                Log.infof("Ingested %d metrics. Skipped %d metrics. Duration: %s", count, skipped, Duration.between(startTime, Instant.now()));
            }
        });
    }

    public void insertRow(AggregatedDataWindowed row) {
        if (!olapConfig.enabled()) {
            return;
        }
        rowBuffer.add(row);
        if (rowBuffer.size() >= olapConfig.databaseMaxBufferedRows()) {
            CompletableFuture.runAsync(() -> {
                try {
                    flushToDb();
                } catch (Exception e) {
                    Log.error("Failed to flush to DB", e);
                }
            });
        }
    }

    // Deprecated because tags should not be used in OLAP mode (or anywhere else, for that matter)
    // They lose all meaning upon aggregation in the MetricEnricher
    @Deprecated
    public Set<String> getAllTagKeys() {
        return getAllJsonKeys("tags");
    }

    public Set<String> getAllContextKeys() {
        return Collections.unmodifiableSet(contextKeys);
    }

    public Set<String> getAllMetrics() {
        return olapInfra.getConnection()
                .map(conn -> {
                    DSLContext dslContext = DSL.using(conn);
                    AggregatedData a = AGGREGATED_DATA.as("a");
                    return dslContext
                            .selectDistinct(a.INITIAL_METRIC_NAME)
                            .from(a)
                            .stream()
                            .map(Record1::value1)
                            .collect(Collectors.toSet());
                })
                .orElse(new HashSet<>());
    }

    private Set<String> getAllJsonKeys(String column) {
        return olapInfra.getConnection()
                .map(conn -> {
                    try (var statement = conn.prepareStatement("SELECT unnest(json_keys( " + column + " )) FROM aggregated_data")) {
                        return getStatementResultAsStrings(statement, true);
                    } catch (Exception e) {
                        Log.error("Failed to get keys of column: " + column, e);
                    }
                    return new HashSet<String>();
                })
                .orElse(new HashSet<>());
    }

    private Set<String> getAllJsonKeyValues(String column, String key) {
        ensureIdentifierIsSafe(key);
        return olapInfra.getConnection()
                .map(conn -> {
                    try (var statement = conn.prepareStatement("SELECT DISTINCT %s->>'%s' FROM aggregated_data".formatted(column, key))) {
                        return getStatementResultAsStrings(statement, false);
                    } catch (Exception e) {
                        Log.error("Failed to get keys of column: " + column, e);
                    }
                    return new HashSet<String>();
                })
                .orElse(new HashSet<>());
    }

    private Set<String> getStatementResultAsStrings(PreparedStatement statement, boolean removeBrackets) throws SQLException {
        var result = statement.executeQuery();
        var keys = new HashSet<String>();
        while (result.next()) {
            var keyValue = result.getString(1);
            if (keyValue != null) {
                keys.add(removeBrackets && keyValue.endsWith("]") && keyValue.startsWith("[") ?
                        keyValue.substring(1, keyValue.length() - 1) : keyValue);
            }
        }
        return keys;
    }

    // Deprecated because tags should not be used in OLAP mode (or anywhere else, for that matter)
    // They lose all meaning upon aggregation in the MetricEnricher
    @Deprecated
    public Set<String> getAllTagValues(String tagKey) {
        return getAllJsonKeyValues("tags", tagKey);
    }

    public Set<String> getAllContextValues(String contextKey) {
        return getAllJsonKeyValues("context", contextKey);
    }

    public Path exportData(Instant startDate, Instant endDate, String format) {
        var finalFormat = (format == null ? "csv" : format).toLowerCase();
        var finalStartDate = startDate == null ? Instant.now().minus(Duration.ofDays(30)) : startDate;
        var finalEndDate = endDate == null ? Instant.now() : endDate;

        Log.infof("Generating report for the period from %s to %s", finalStartDate, finalEndDate);

        var tmpFileName = Path.of(System.getProperty("java.io.tmpdir"), "olap_export_" + UUID.randomUUID() + "." + finalFormat);
        return olapInfra.getConnection().map((conn) -> {
            try (var statement = conn.prepareStatement("COPY (SELECT * FROM aggregated_data WHERE start_time >= ? AND end_time <= ?) TO '" + tmpFileName + "'"
                    + (finalFormat.equals("csv") ? "(HEADER, DELIMITER ',')" : ""))) {
                statement.setObject(1, finalStartDate.atOffset(ZoneOffset.UTC));
                statement.setObject(2, finalEndDate.atOffset(ZoneOffset.UTC));
                statement.execute();
                return tmpFileName;
            } catch (SQLException e) {
                Log.error("Failed to export data", e);
                return null;
            }
        }).orElse(null);
    }

    public Map<String, Collection<MetricHistoryTO>> exportDataAggregated(Instant startDate, Instant endDate, String groupByContextKey) {
        var finalStartDate = startDate == null ? Instant.now().minus(Duration.ofDays(30)) : startDate;
        var finalEndDate = endDate == null ? Instant.now() : endDate;

        var bucketWidth = Duration.between(finalStartDate, finalEndDate).toHours();
        Map<String, Collection<MetricHistoryTO>> metricToAggregatedValue = metricNameRepository.getMetricNames().stream()
                .map(MetricNameEntity::metricName)
                .collect(Collectors.toMap(
                        metric -> metric,
                        metric -> getHistoryGrouped(finalStartDate, finalEndDate, Set.of(metric), groupByContextKey, bucketWidth)
                ));
        return metricToAggregatedValue;
    }


    public List<MetricEO> getHistory(Instant startDate, Instant endDate, Set<String> names) {
        return olapInfra.getConnection().map((conn) -> {
            var finalStartDate = startDate == null ? Instant.now().minus(Duration.ofDays(30)) : startDate;
            var finalEndDate = endDate == null ? Instant.now() : endDate;
            Log.infof("Generating report for the period from %s to %s", finalStartDate, finalEndDate);

            DSLContext dslContext = DSL.using(conn);
            AggregatedData a = AGGREGATED_DATA.as("a");

            Condition condition = a.START_TIME.ge(finalStartDate.atOffset(ZoneOffset.UTC))
                    .and(a.END_TIME.le(finalEndDate.atOffset(ZoneOffset.UTC)));
            if (!names.isEmpty()) {
                condition = condition.and(a.INITIAL_METRIC_NAME.in(names));
            }

            Result<AggregatedDataRecord> fetchResult = dslContext
                    .selectFrom(a)
                    .where(condition)
                    .fetch();

            return fetchResult.stream()
                    .map(record -> new MetricEO(
                            record.getId(),
                            record.getStartTime().toInstant(),
                            record.getEndTime().toInstant(),
                            record.getInitialMetricName(),
                            record.getEntityType(),
                            record.getName(),
                            parseMap(record.getTags().data()),
                            parseMap(record.getContext().data()),
                            record.getValue(),
                            record.getTarget()
                    )).toList();
        }).orElse(Collections.emptyList());
    }

    public Collection<MetricHistoryTO> getHistoryGrouped(Instant startDate, Instant endDate, Set<String> names, String groupByContextKey) {
        return getHistoryGrouped(startDate, endDate, names, groupByContextKey, null);
    }

    /**
     * Get aggregated metric history, grouped by a context key, with optional grouping by time buckets within the time range.
     * Note that not grouping by time buckets is equivalent to setting the bucket width to the entire time range.
     *
     * @param startDate Start time of the query range. If null, defaults to 30 days ago.
     * @param endDate End time of the query range. If null, defaults to now.
     * @param metricName Set of metric names to filter by. If empty, includes all metrics.
     * @param groupByContextKey The context key to group by. Must be a valid JSON key in the context field.
     * @param timeBucketWidthHours Optional width of time buckets in hours. If null, then the bucket width will be between 1 and 24 hours, depending on the size of the time range. If bucketing by time window is not desired, set it to the hours between startDate and endDate.
     * @return A collection of MetricHistoryTO objects, each representing a unique value of the specified context key, containing lists of timestamps and corresponding aggregated metric values.
     */
    public Collection<MetricHistoryTO> getHistoryGrouped(@Nullable Instant startDate, @Nullable Instant endDate, Set<String> metricName, String groupByContextKey, @Nullable Long timeBucketWidthHours) {
        return olapInfra.getConnection().map((conn) -> {
            var finalStartDate = startDate == null ? Instant.now().minus(Duration.ofDays(30)) : startDate;
            var finalEndDate = endDate == null ? Instant.now() : endDate;
            Log.infof("Generating report for the period from %s to %s, grouped for context '%s'", finalStartDate, finalEndDate, groupByContextKey);

            DSLContext dslContext = DSL.using(conn);
            AggregatedData a = AGGREGATED_DATA.as("a");

            Condition condition = a.START_TIME.ge(finalStartDate.atOffset(ZoneOffset.UTC))
                    .and(a.END_TIME.le(finalEndDate.atOffset(ZoneOffset.UTC)));
            if (!metricName.isEmpty()) {
                condition = condition.and(a.INITIAL_METRIC_NAME.in(metricName));
            }

            var contextField = DSL.coalesce(DSL.jsonValue(a.CONTEXT, groupByContextKey), DSL.val("unknown")).as("context_value");
            var totalValue = DSL.sum(a.VALUE);
            var timespanWidth = Duration.between(finalStartDate, finalEndDate).toDays();
            // if we do not group by hour, create one big bucket for the entire timespan
            var bucketWidth = timeBucketWidthHours == null
                    ? DSL.field("INTERVAL %d HOUR".formatted(Math.min(24, Math.max(1, timespanWidth))))
                    : DSL.field("INTERVAL %d SECONDS".formatted(Math.min(3600 * timeBucketWidthHours, Duration.between(finalStartDate, finalEndDate).toSeconds())));
            var tb = DSL.function("time_bucket", OffsetDateTime.class, bucketWidth, a.START_TIME, DSL.val(finalStartDate)).as("time_bucket");
            var dslQuery = dslContext
                    .select(contextField, tb, totalValue)
                    .from(a)
                    .where(condition)
                    .groupBy(contextField, tb);

            Log.debugf("Executing query: %s", dslQuery);

            var fetchResult = dslQuery.fetch();
            Map<String, MetricHistoryTO> metrics = new HashMap<>();
            fetchResult.forEach(record -> {
                var contextValue = record.get(contextField).data();
                var metricHistory = metrics.computeIfAbsent(contextValue, k -> new MetricHistoryTO(
                        contextValue,
                        Map.of(groupByContextKey, contextValue),
                        new ArrayList<>(),
                        new ArrayList<>()
                ));
                var startTime = record.get(tb).toInstant();
                metricHistory.getTimes().add(startTime);
                metricHistory.getValues().add(record.get(totalValue).doubleValue());
            });
            return metrics.values();
        }).orElse(Collections.emptyList());
    }

    private Map<String, String> parseMap(String content) {
        try {
            return OBJECT_MAPPER.readValue(content, MAP_STRING_STRING_TYPE_REF);
        } catch (JsonProcessingException e) {
            Log.errorf(e, "Failed to parse map content: %s", content);
        }
        return Collections.emptyMap();
    }

}
