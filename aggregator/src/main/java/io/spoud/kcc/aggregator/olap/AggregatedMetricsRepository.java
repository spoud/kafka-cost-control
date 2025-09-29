package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.data.EntityType;
import io.spoud.kcc.data.SingleContextData;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;

import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Startup
@ApplicationScoped
public class AggregatedMetricsRepository {
    public static final TypeReference<Map<String, String>> MAP_STRING_STRING_TYPE_REF = new TypeReference<>() {
    };

    private final OlapConfigProperties olapConfig;
    private final BlockingQueue<AggregatedDataWindowed> rowBuffer;
    private final MetricNameRepository metricNameRepository;
    private final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private Connection connection;

    public AggregatedMetricsRepository(OlapConfigProperties olapConfig, MetricNameRepository metricNameRepository) {
        this.olapConfig = olapConfig;
        this.rowBuffer = new ArrayBlockingQueue<>(olapConfig.databaseMaxBufferedRows());
        this.metricNameRepository = metricNameRepository;
    }

    private static void ensureIdentifierIsSafe(String identifier) {
        if (!identifier.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid identifier. Expected only letters, numbers, and underscores");
        }
    }

    public boolean isOlapEnabled() {
        return olapConfig.enabled();
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
                importData();
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

    @Scheduled(every = "${cc.olap.database.flush-interval.seconds}s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    synchronized void flushToDb() {
        getConnection().ifPresent((conn) -> {
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

    public void insertRow(SingleContextData row) {
        for (var metric : row.getAggregatedMetrics().entrySet()) {
            var metricName = metric.getKey();
            var metricValue = metric.getValue();
            var metricCost = Optional.ofNullable(row.getAggregatedCosts())
                    .map(costs -> costs.getOrDefault(metricName, 0d))
                    .orElse(0d);
            var aggregatedRow = AggregatedDataWindowed.newBuilder()
                    .setStartTime(row.getStartTime())
                    .setEndTime(row.getEndTime())
                    .setInitialMetricName(metricName)
                    .setEntityType(EntityType.UNKNOWN)
                    .setName(row.getName())
                    .setTags(Collections.emptyMap())
                    .setContext(Map.of(row.getContextType(), row.getName()))
                    .setValue(metricValue)
                    .setCost(metricCost)
                    .build();
            insertRow(aggregatedRow);
        }
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

    public Collection<MetricHistoryTO> getHistoryGrouped(Instant startDate, Instant endDate, Set<String> names, String groupByContextKey) {
        return getHistoryGrouped(startDate, endDate, names, groupByContextKey, true);
    }

    public Collection<MetricHistoryTO> getHistoryGrouped(Instant startDate, Instant endDate, Set<String> names, String groupByContextKey, boolean groupByHour) {
        return getConnection().map((conn) -> {
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

    private void importData() {
        try {
            olapConfig.databaseSeedDataPath().ifPresent(this::importSeedData);
            olapConfig.insertSyntheticDays().ifPresent(this::insertSyntheticDays);
            addExistingMetrics();
        } catch (Exception e) {
            Log.warn("Failed to load seed data", e);
        }
    }

    public void importSeedData(String path) {
        getConnection().ifPresent((conn) -> {
            loadDataExport(path, conn);
        });
    }

    private void insertSyntheticDays(int days) {
        getConnection().ifPresent(conn -> insertGeneratedData(conn, days));
    }

    private void insertGeneratedData(Connection conn, int days) {
        ObjectMapper objectMapper = new ObjectMapper();
        var rnd = new Random();
        long seed = rnd.nextLong();
        rnd.setSeed(seed);
        Log.infof("Inserting data with seed %d", seed);

        var metrics = List.of("kafka_log_log_size", "kafka_server_brokertopicmetrics_bytesin_total", "kafka_server_brokertopicmetrics_bytesout_total");
        var readers = List.of("DataAnalyticsConsumer", "RealTimeDashboardService", "InventoryUpdateProcessor", "FraudDetectionEngine", "CustomerNotificationService");
        var writers = List.of("OrderPlacementService", "LogAggregationService", "UserActivityProducer", "PaymentGatewayEmitter", "SensorDataCollector");
        var topics = List.of("orders.transactions", "system.application.logs", "user.activity.events", "payments.processed", "sensor.data.raw");
        var applications = List.of("RealtimeMetricsAggregator", "CustomerOrderStreamer", "FinancialTransactionProcessor", "LogEventAnalyzer", "InventoryUpdateEmitter");
        var names = List.of("school.principals.management", "education.principals.directory", "admin.principals.records", "staff.principals.updates", "district.principals.roster");

        try (var stmt = conn.prepareStatement("INSERT OR REPLACE INTO aggregated_data VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Instant startTime = Instant.now().truncatedTo(ChronoUnit.HOURS), middle = startTime.minus(Duration.ofDays(days / 2));
                 startTime.isAfter(Instant.now().minus(Duration.ofDays(days)));
                 startTime = startTime.minus(Duration.ofHours(1))) {
                var endTime = startTime.plus(Duration.ofHours(1));

                int j;
                if (startTime.isAfter(middle)) {
                    j = 5; // one reader / writer / topic / application / name only appears after half-time
                } else {
                    j = 4;
                }
                for (int i = 0; i < j; i++) {
                    var reader = readers.get(i);
                    var writer = writers.get(i);
                    var topic = topics.get(i);
                    var application = applications.get(i);
                    ObjectNode json = objectMapper.createObjectNode();
                    json.put("readers", reader);
                    json.put("writers", writer);
                    json.put("topic", topic);
                    json.put("application", application);
                    var context = json.toString();

                    for (String metric : metrics) {
                        int value;
                        if (Math.random() > 0.8) {
                            value = rnd.nextInt(150_000_000);
                        } else {
                            value = rnd.nextInt(30_000_000);
                        }
                        var scale = (i + 1) / 3.0;
                        value = (int) (value * scale);
                        stmt.setObject(1, startTime.atOffset(ZoneOffset.UTC));
                        stmt.setObject(2, endTime.atOffset(ZoneOffset.UTC));
                        stmt.setString(3, metric);
                        stmt.setString(4, "TOPIC");
                        stmt.setString(5, names.get(i));
                        stmt.setObject(6, "{}");
                        stmt.setString(7, context);
                        stmt.setDouble(8, value);
                        stmt.setString(9, "target_" + i);
                        stmt.setString(10, UUID.randomUUID().toString());
                        stmt.addBatch();
                    }
                }
            }
            stmt.executeBatch();
            Log.infov("Successfully inserted dummy data");
        } catch (SQLException e) {
            Log.error("Failed to insert dummy data", e);
        }
    }

    private void loadDataExport(String path, Connection conn) {
        try (var statement = conn.prepareStatement("COPY aggregated_data FROM '" + path + "'  (AUTO_DETECT true)")) {
            statement.execute();
            Log.infof("Loaded seed data from %s", path);
        } catch (SQLException e) {
            Log.error("Failed to load data", e);
        }
    }

    private void addExistingMetrics() {
        getConnection().ifPresent(conn -> {
            try (PreparedStatement statement = conn.prepareStatement("SELECT DISTINCT initial_metric_name FROM aggregated_data");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String metricName = resultSet.getString("initial_metric_name");
                    metricNameRepository.addMetricName(metricName, Instant.now());
                }
            } catch (SQLException e) {
                Log.error("Failed to add metrics", e);
            }
        });
    }
}
