package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.data.AggregatedDataWindowed;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;

import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

@Startup
@RequiredArgsConstructor
@ApplicationScoped
public class AggregatedMetricsRepository {
    private final OlapConfigProperties olapConfig;

    private Connection connection;
    private final Queue<AggregatedDataWindowed> rowBuffer = new ConcurrentLinkedQueue<>();
    private final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @PostConstruct
    public void init() {
        if (olapConfig.enabled()) {
            getConnection().ifPresent((conn) -> {
                try {
                    createTableIfNotExists(conn);
                } catch (SQLException e) {
                    Log.error("Failed to create OLAP table", e);
                }
            });
        } else {
            Log.info("OLAP module is disabled.");
        }
    }

    private Optional<Connection> getConnection() {
        try {
            if (connection == null) {
                Class.forName("org.duckdb.DuckDBDriver"); // force load the driver
                connection = DriverManager.getConnection(olapConfig.databaseUrl());
            }
            return Optional.of(connection);
        } catch (Exception e) {
            Log.warn("Failed to get read-write connection to OLAP database", e);
            return Optional.empty();
        }
    }

    private void createTableIfNotExists(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + olapConfig.fqTableName() + " (" +
                    "start_time TIMESTAMP_MS NOT NULL, " +
                    "end_time TIMESTAMP_MS NOT NULL, " +
                    "initial_metric_name VARCHAR NOT NULL, " +
                    "entity_type VARCHAR NOT NULL, " +
                    "name VARCHAR NOT NULL, " +
                    "tags JSON NOT NULL, " +
                    "context JSON NOT NULL, " +
                    "value DOUBLE NOT NULL," +
                    "target VARCHAR NOT NULL, " +
                    "id VARCHAR PRIMARY KEY)");
            Log.infof("Created OLAP DB table: %s", olapConfig.fqTableName());
        }
    }

    @Scheduled(every = "${cc.olap.database.flush-interval.seconds}s")
    public void flushToDb() {
        getConnection().ifPresent((conn) -> {
            var skipped = 0;
            var count = 0;
            var startTime = Instant.now();
            try (var stmt = conn.prepareStatement("INSERT OR REPLACE INTO " + olapConfig.fqTableName() + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (var metric = rowBuffer.poll(); metric != null; metric = rowBuffer.poll()) {
                    Log.debugv("Ingesting metric: {0}", metric);
                    var start = Timestamp.from(metric.getStartTime());
                    var end = Timestamp.from(metric.getEndTime());
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
                    stmt.setTimestamp(1, start);
                    stmt.setTimestamp(2, end);
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
            flushToDb();
        }
    }

    public Set<String> getAllTagKeys() {
        return getAllJsonKeys("tags");
    }

    public Set<String> getAllContextKeys() {
        return getAllJsonKeys("context");
    }

    public Set<String> getAllMetrics() {
        return getConnection()
                .map(conn -> {
                    try (var statement = conn.prepareStatement("SELECT DISTINCT initial_metric_name FROM " + olapConfig.fqTableName())) {
                        var result = statement.executeQuery();
                        var metrics = new HashSet<String>();
                        while (result.next()) {
                            metrics.add(result.getString(1));
                        }
                        return metrics;
                    } catch (SQLException e) {
                        Log.error("Failed to get all metrics", e);
                    }
                    return new HashSet<String>();
                })
                .orElse(new HashSet<>());
    }

    private Set<String> getAllJsonKeys(String column) {
        return getConnection()
                .map(conn -> {
                    try (var statement = conn.prepareStatement("SELECT unnest(json_keys( " + column + " )) FROM " + olapConfig.fqTableName())) {
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
        return getConnection()
                .map(conn -> {
                    try (var statement = conn.prepareStatement("SELECT DISTINCT " + column + "->>'" + key + "' FROM " + olapConfig.fqTableName())) {
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

    private static void ensureIdentifierIsSafe(String identifier) {
        if (!identifier.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid identifier. Expected only letters, numbers, and underscores");
        }
    }

    public Path exportData(LocalDateTime startDate, LocalDateTime endDate, String format) {
        var finalFormat = (format == null ? "csv" : format).toLowerCase();
        var finalStartDate = startDate == null ? LocalDateTime.now().minus(Duration.ofDays(30)) : startDate;
        var finalEndDate = endDate == null ? LocalDateTime.now() : endDate;

        Log.infof("Generating report for the period from %s to %s", finalStartDate, finalEndDate);

        var tmpFileName = Path.of(System.getProperty("java.io.tmpdir"), "olap_export_" + UUID.randomUUID() + "." + finalFormat);
        return getConnection().map((conn) -> {
            try (var statement = conn.prepareStatement("COPY (SELECT * FROM " + olapConfig.fqTableName() + " WHERE start_time >= ? AND end_time <= ?) TO '" + tmpFileName + "'"
                    + (finalFormat.equals("csv") ? "(HEADER, DELIMITER ',')" : ""))) {
                statement.setTimestamp(1, Timestamp.valueOf(finalStartDate));
                statement.setTimestamp(2, Timestamp.valueOf(finalEndDate));
                statement.execute();
                return tmpFileName;
            } catch (SQLException e) {
                Log.error("Failed to export data", e);
                return null;
            }
        }).orElse(null);
    }
}
