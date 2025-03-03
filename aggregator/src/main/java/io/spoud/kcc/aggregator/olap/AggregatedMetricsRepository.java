package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.data.AggregatedDataWindowed;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Startup
@RequiredArgsConstructor
@ApplicationScoped
public class AggregatedMetricsRepository {
    public static final TypeReference<Map<String, String>> MAP_STRING_STRING_TYPE_REF = new TypeReference<>() {
    };

    private final OlapConfigProperties olapConfig;
    private final Queue<AggregatedDataWindowed> rowBuffer = new ConcurrentLinkedQueue<>();
    private final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private Connection connection;

    private static void ensureIdentifierIsSafe(String identifier) {
        if (!identifier.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid identifier. Expected only letters, numbers, and underscores");
        }
    }

    @PostConstruct
    public void init() {
        if (olapConfig.enabled()) {
            Log.infof("OLAP module is enabled. Data will be written to %s", olapConfig.databaseUrl());
            getConnection().ifPresent((conn) -> {
                try {
                    createTableIfNotExists(conn);
                } catch (SQLException e) {
                    Log.error("Failed to create OLAP table", e);
                }
                setDbMemoryLimit(conn);
                try {
                    olapConfig.databaseSeedDataPath().ifPresent(this::loadDataExport);
                } catch (Exception e) {
                    Log.warn("Failed to load seed data", e);
                }
            });
        } else {
            Log.info("OLAP module is disabled.");
        }
    }

    @Shutdown
    public void cleanUp() {
        if (olapConfig.enabled()) {
            Log.info("Shutting down OLAP module. Performing final flush and closing connection...");
            flushToDb();
            getConnection().ifPresent((conn) -> {
                try {
                    conn.close();
                    Log.info("Closed OLAP database connection");
                } catch (SQLException e) {
                    Log.error("Failed to close OLAP database connection", e);
                }
            });
        }
    }

    private void setDbMemoryLimit(Connection conn) {
        if (olapConfig.databaseMemoryLimitMib().isEmpty()) {
            Log.warn("""
                    No memory limit set for OLAP database. Using DuckDB default of 80% of system memory.
                    Please consider setting a limit to avoid out-of-memory errors, by setting the `cc.olap.total.memory.limit.mb` and `cc.olap.database.memory.limit.percent` properties.
                    """);
            return;
        }
        var memLimit = olapConfig.databaseMemoryLimitMib().orElseThrow();
        try (var statement = conn.createStatement()) {
            statement.execute("SET memory_limit = '" + memLimit + "MiB'");
            Log.infof("Set memory limit for OLAP database to %d MiB", memLimit);
        } catch (SQLException e) {
            Log.error("Failed to set memory limit for OLAP database", e);
        }
    }

    private Optional<Connection> getConnection() {
        if (!olapConfig.enabled()) {
            return Optional.empty();
        }
        try {
            if (connection == null || connection.isClosed()) {
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
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS aggregated_data (
                        start_time TIMESTAMPTZ NOT NULL,
                        end_time TIMESTAMPTZ NOT NULL,
                        initial_metric_name VARCHAR NOT NULL,
                        entity_type VARCHAR NOT NULL,
                        name VARCHAR NOT NULL,
                        tags JSON NOT NULL,
                        context JSON NOT NULL,
                        value DOUBLE NOT NULL,
                        target VARCHAR NOT NULL,
                        id VARCHAR PRIMARY KEY
                    )
                    """);
            Log.infof("Created OLAP DB table: aggregated_data");
        }
    }

    @Scheduled(every = "${cc.olap.database.flush-interval.seconds}s")
    public void flushToDb() {
        getConnection().ifPresent((conn) -> {
            var skipped = 0;
            var count = 0;
            var startTime = Instant.now();
            try (var stmt = conn.prepareStatement("INSERT OR REPLACE INTO aggregated_data VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (var metric = rowBuffer.poll(); metric != null; metric = rowBuffer.poll()) {
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
                    try (var statement = conn.prepareStatement("SELECT DISTINCT initial_metric_name FROM aggregated_data")) {
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
        return getConnection()
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
        return getConnection().map((conn) -> {
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


    public List<MetricEO> getHistory(Instant startDate, Instant endDate, Set<String> names) {
        return getConnection().map((conn) -> {
            var finalStartDate = startDate == null ? Instant.now().minus(Duration.ofDays(30)) : startDate;
            var finalEndDate = endDate == null ? Instant.now() : endDate;
            Log.infof("Generating report for the period from %s to %s", finalStartDate, finalEndDate);

            var nameFilter = names.isEmpty() ? "" : " AND initial_metric_name IN " + names.stream().map(s -> "?")
                    .collect(Collectors.joining(", ", "(", ")"));
            try (var statement = conn.prepareStatement("""
                    SELECT * FROM aggregated_data
                    WHERE start_time >= ? AND end_time <= ?
                    """ + nameFilter)) {
                statement.setObject(1, finalStartDate.atOffset(ZoneOffset.UTC));
                statement.setObject(2, finalEndDate.atOffset(ZoneOffset.UTC));
                var i = 3;
                for (var name : names) {
                    statement.setString(i, name);
                    i++;
                }
                List<MetricEO> metrics = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        metrics.add(new MetricEO(
                                resultSet.getString("id"),
                                resultSet.getTimestamp("start_time").toInstant(),
                                resultSet.getTimestamp("end_time").toInstant(),
                                resultSet.getString("initial_metric_name"),
                                resultSet.getString("entity_type"),
                                resultSet.getString("name"),
                                parseMap(resultSet.getString("tags")),
                                parseMap(resultSet.getString("context")),
                                resultSet.getDouble("value"),
                                resultSet.getString("target")
                        ));
                    }
                }
                return metrics;
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

    public void loadDataExport(String path) {
        getConnection().ifPresent((conn) -> {
            try (var statement = conn.prepareStatement("COPY aggregated_data FROM '" + path + "'  (AUTO_DETECT true)")) {
                statement.execute();
                Log.infof("Loaded seed data from %s", path);
            } catch (SQLException e) {
                Log.error("Failed to load data", e);
            }
        });
    }
}
