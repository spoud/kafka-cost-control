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
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.impl.DSL;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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

    public AggregatedMetricsRepository(OlapConfigProperties olapConfig, OlapInfra olapInfra, MetricNameRepository metricNameRepository) {
        this.olapConfig = olapConfig;
        this.rowBuffer = new ArrayBlockingQueue<>(olapConfig.databaseMaxBufferedRows());
        this.olapInfra = olapInfra;
        this.metricNameRepository = metricNameRepository;
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

    public Set<String> getAllTagKeys() {
        return getAllJsonKeys("tags");
    }

    public Set<String> getAllContextKeys() {
        return getAllJsonKeys("context");
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

        Map<String, Collection<MetricHistoryTO>> metricToAggregatedValue = metricNameRepository.getMetricNames().stream()
                .map(MetricNameEntity::metricName)
                .collect(Collectors.toMap(
                        metric -> metric,
                        metric -> getHistoryGrouped(finalStartDate, finalEndDate, Set.of(metric), groupByContextKey, false)
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
        return getHistoryGrouped(startDate, endDate, names, groupByContextKey, true);
    }

    public Collection<MetricHistoryTO> getHistoryGrouped(Instant startDate, Instant endDate, Set<String> names, String groupByContextKey, boolean groupByHour) {
        return olapInfra.getConnection().map((conn) -> {
            var finalStartDate = startDate == null ? Instant.now().minus(Duration.ofDays(30)) : startDate;
            var finalEndDate = endDate == null ? Instant.now() : endDate;
            Log.infof("Generating report for the period from %s to %s, grouped for context '%s'", finalStartDate, finalEndDate, groupByContextKey);

            var nameFilter = names.isEmpty() ? "" : " AND initial_metric_name IN " + names.stream().map(s -> "?")
                    .collect(Collectors.joining(", ", "(", ")"));
            // we use a subquery so we can guarantee binding of the same value for `json_value(context, ?)`
            // which is used in select and in group by clause
            String sql =
                    "select subquery.context" + (groupByHour ? ", subquery.start_time" : "") + ", sum(subquery.value) as sum " +
                            """
                                    from (
                                           select json_value(context, ?) as context, start_time, value
                                           from aggregated_data
                                           where start_time >= ? and end_time <= ?
                                    """ + nameFilter + ") as subquery" +
                            " group by context " + (groupByHour ? ", start_time order by start_time" : "");
            try (var statement = conn.prepareStatement(sql)) {
                statement.setString(1, groupByContextKey);
                statement.setObject(2, finalStartDate.atOffset(ZoneOffset.UTC));
                statement.setObject(3, finalEndDate.atOffset(ZoneOffset.UTC));
                var i = 4;
                for (var name : names) {
                    statement.setString(i, name);
                    i++;
                }
                Map<String, MetricHistoryTO> metrics = new HashMap<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String nullableContext = resultSet.getString("context");
                        String context = nullableContext == null ? "_unknown" : nullableContext.replace("\"", "");
                        Instant startTime = groupByHour ?
                                resultSet.getTimestamp("start_time").toInstant() :
                                null;
                        double value = resultSet.getDouble("sum");
                        metrics.compute(context, (k, v) -> {
                                    if (v == null) {
                                        return new MetricHistoryTO(context, Map.of(), startTime != null ? new ArrayList<>(List.of(startTime)) : List.of(), new ArrayList<>(List.of(value)));
                                    } else {
                                        v.getTimes().add(startTime);
                                        v.getValues().add(value);
                                        return v;
                                    }
                                }
                        );
                    }
                }
                return metrics.values();
            } catch (SQLException e) {
                Log.error("Failed to export data", e);
                return null;
            }
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
