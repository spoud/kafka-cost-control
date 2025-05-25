package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.AtomicDouble;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.data.AggregatedDataWindowed;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;
import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBDriver;

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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
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
    private final MeterRegistry meterRegistry;
    private DuckDBConnection connection;
    private final AtomicDouble latestMemoryUsage = new AtomicDouble(0.0);

    public AggregatedMetricsRepository(OlapConfigProperties olapConfig, MetricNameRepository metricNameRepository, MeterRegistry meterRegistry) {
        this.olapConfig = olapConfig;
        this.rowBuffer = new ArrayBlockingQueue<>(olapConfig.databaseMaxBufferedRows());
        this.metricNameRepository = metricNameRepository;
        this.meterRegistry = meterRegistry;
    }

    private static void ensureIdentifierIsSafe(String identifier) {
        if (!identifier.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid identifier. Expected only letters, numbers, and underscores");
        }
    }

    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void printDbInfo() {
        var writeUsage = getMemoryUsage(connection);
        Log.infof("OLAP DB memory usage: %.2f MiB", writeUsage / 1024 / 1024);
    }

    @PostConstruct
    public void init() {
        if (olapConfig.enabled()) {
            Log.infof("OLAP module is enabled. Data will be written to %s", olapConfig.databaseUrl());
            try {
                Class.forName("org.duckdb.DuckDBDriver"); // force load the driver
                Properties props = new Properties();
                props.setProperty(DuckDBDriver.JDBC_STREAM_RESULTS, String.valueOf(true));
                connection = (DuckDBConnection) DriverManager.getConnection(olapConfig.databaseUrl(), props);
                Log.info("Connections have been created");
            } catch (Exception e) {
                Log.error("Failed to load DuckDB driver", e);
                throw new RuntimeException("Failed to load DuckDB driver", e);
            }
            withConnection((conn) -> {
                try {
                    createTableIfNotExists(conn);
                } catch (SQLException e) {
                    Log.error("Failed to create OLAP table", e);
                }
                setDbParameters(conn);
                importData();
                meterRegistry.gauge("olap.memory.usage", latestMemoryUsage);
            });
        } else {
            Log.info("OLAP module is disabled.");
        }
    }

    private double getMemoryUsage(Connection c) {
        try {
            var res = c.createStatement()
                    .executeQuery("SELECT tag, memory_usage_bytes FROM duckdb_memory();");
            double totalMemory = 0.0; // instead of doing SUM, we sum manually because we are also interested in individual memory usage
            while (res.next()) {
                var tag = res.getString(1);
                var usageMb = res.getDouble(2) / 1024 / 1024;
                Log.debugv("Memory usage for tag {0}: {1} MiB", tag, usageMb);
                totalMemory += res.getDouble(2);
            }
            return totalMemory;
        } catch (SQLException e) {
            Log.error("Failed to get OLAP memory usage", e);
            return 0.0;
        }
    }

    @Shutdown
    public void cleanUp() throws SQLException {
        if (olapConfig.enabled()) {
            Log.info("Shutting down OLAP module. Performing final flush and closing connection...");
            flushToDb();
            connection.close();
        }
    }

    private void setDbParameters(Connection conn) {
        try (var statement = conn.createStatement()) {
            statement.execute("SET preserve_insertion_order = false");
            Log.info("Set preserve_insertion_order to false");
        } catch (SQLException e) {
            Log.error("Failed to set preserve_insertion_order", e);
        }
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

    private <T> Optional<T> withConnection(Function<Connection, T> function) {
        try (var c = connection.duplicate()) {
            var res = Optional.of(function.apply(c));
            latestMemoryUsage.set(getMemoryUsage(c));
            return res;
        } catch (Exception e) {
            Log.error("Failed to get read-write connection to OLAP database", e);
            return Optional.empty();
        }
    }

    private void withConnection(Consumer<Connection> consumer) {
        Log.info("Acquiring connection to OLAP database...");
        DriverManager.setLoginTimeout(10);
        try (var c = connection.duplicate()) {
            Log.info("Connection acquired");
            consumer.accept(c);
            latestMemoryUsage.set(getMemoryUsage(c));
        } catch (Exception e) {
            Log.error("Failed to get read-write connection to OLAP database", e);
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
                        id VARCHAR
                    )
                    """);
            Log.infof("Created OLAP DB table: aggregated_data");
        }
    }

    @Scheduled(every = "${cc.olap.database.flush-interval.seconds}s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    synchronized void flushToDb() {
        if (rowBuffer.isEmpty()) {
            return;
        }
        withConnection((conn) -> {
            // Drain the buffer. This flush will deal only with the current buffer elements, not with any new rows added while this flush is running
            // This prevents the potential edge-case where the buffer is emptied and filled at the same rate, causing the flush to never finish.
            // Note that since adding to the buffer happens from the stream processing thread, this carries a slight risk of slowing down the stream processing.
            var finalRowBuffer = new ArrayDeque<AggregatedDataWindowed>(rowBuffer.size());
            rowBuffer.drainTo(finalRowBuffer);
            var skipped = 0;
            var count = 0;
            if (finalRowBuffer.isEmpty()) {
                return;
            }
            var startTime = Instant.now();
            try (var stmt = conn.prepareStatement("INSERT INTO aggregated_data VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
        try {
            boolean success = rowBuffer.offer(row, 30000, TimeUnit.MILLISECONDS);
            if (!success) {
                Log.warn("Failed to insert row to buffer. Row was dropped.");
            }
        } catch (InterruptedException ignored) {
            Log.warn("Interrupted while inserting row to buffer. Ignoring...");
        }
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
        return withConnection(conn -> {
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
        return withConnection(conn -> {
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
        return withConnection(conn -> {
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
        return withConnection((conn) -> {
            try (var statement = conn.prepareStatement("""
                    COPY (
                        SELECT aggdata.id, ANY_VALUE(aggdata.start_time) AS start_time, ANY_VALUE(aggdata.end_time) AS end_time, ANY_VALUE(aggdata.initial_metric_name) AS initial_metric_name, ANY_VALUE(aggdata.entity_type) AS entity_type, ANY_VALUE(aggdata.name) AS name, ANY_VALUE(aggdata.tags) AS tags, ANY_VALUE(aggdata.context) AS context, ANY_VALUE(aggdata.target) AS target, MAX(aggdata.value) AS value
                        FROM aggregated_data aggdata
                        WHERE aggdata.start_time >= ? AND aggdata.end_time <= ?
                        GROUP BY id
                    ) TO '""" + tmpFileName + "'" + (finalFormat.equals("csv") ? "(HEADER, DELIMITER ',')" : ""))) {
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
        return withConnection((conn) -> {
            var finalStartDate = startDate == null ? Instant.now().minus(Duration.ofDays(30)) : startDate;
            var finalEndDate = endDate == null ? Instant.now() : endDate;
            Log.infof("Generating report for the period from %s to %s", finalStartDate, finalEndDate);

            var nameFilter = names.isEmpty() ? "" : " AND aggdata.initial_metric_name IN " + names.stream().map(s -> "?")
                    .collect(Collectors.joining(", ", "(", ")"));
            try (var statement = conn.prepareStatement("""
                    SELECT aggdata.id, ANY_VALUE(aggdata.start_time) AS start_time, ANY_VALUE(aggdata.end_time) AS end_time, ANY_VALUE(aggdata.initial_metric_name) AS initial_metric_name, ANY_VALUE(aggdata.entity_type) AS entity_type, ANY_VALUE(aggdata.name) AS name, ANY_VALUE(aggdata.tags) AS tags, ANY_VALUE(aggdata.context) AS context, ANY_VALUE(aggdata.target) AS target, MAX(aggdata.value) AS value
                    FROM aggregated_data aggdata
                    WHERE aggdata.start_time >= ? AND aggdata.end_time <= ?
                    """ + nameFilter + """
                    GROUP BY aggdata.id
                    """)) {
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

    /**
     * Retrieves a collection of grouped metric histories within a given time period.
     * This method fetches metric history data from the database, filters it based on the provided
     * time range and metric names, and groups the data by the specified context key.
     *
     * @param startDate the start of the time range for the query. If null, a default of 30 days before the current date will be used.
     * @param endDate the end of the time range for the query. If null, the current date will be used.
     * @param names a set of metric names to filter the results. If empty, no filtering will occur.
     * @param groupByContextKey the key used to group the metric data based on the context.
     *
     * @return a collection of {@code MetricHistoryTO} objects containing the grouped metric data. If an error occurs or
     *         no data is found, an empty collection may be returned.
     */
    public Collection<MetricHistoryTO> getHistoryGrouped(Instant startDate, Instant endDate, Set<String> names, String groupByContextKey) {
        return withConnection((conn) -> {
            var finalStartDate = startDate == null ? Instant.now().minus(Duration.ofDays(30)) : startDate;
            var finalEndDate = endDate == null ? Instant.now() : endDate;
            Log.infof("Generating report for the period from %s to %s, grouped for context '%s'", finalStartDate, finalEndDate, groupByContextKey);

            var nameFilter = names.isEmpty() ? "" : " AND aggdata.initial_metric_name IN " + names.stream().map(s -> "?")
                    .collect(Collectors.joining(", ", "(", ")"));
            // we use a subquery so we can guarantee binding of the same value for `json_value(context, ?)`
            // which is used in select and in group by clause
            String sql = """
                    select subquery.context, subquery.start_time, sum(subquery.value) as sum
                    from (
                        SELECT aggdata.id, ANY_VALUE(aggdata.start_time) AS start_time, json_value(ANY_VALUE(aggdata.context), ?) AS context, MAX(aggdata.value) AS value
                        FROM aggregated_data aggdata
                        WHERE aggdata.start_time >= ? AND aggdata.end_time <= ?
                    """ + nameFilter + """
                     GROUP BY id
                    ) as subquery""" +
                    " group by context, start_time" +
                    " order by start_time";
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
                        Instant startTime = resultSet.getTimestamp("start_time").toInstant();
                        double value = resultSet.getDouble("sum");
                        metrics.compute(context, (k, v) -> {
                                    if (v == null) {
                                        return new MetricHistoryTO(context, Map.of(), new ArrayList<>(List.of(startTime)), new ArrayList<>(List.of(value)));
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
        withConnection((conn) -> {
            loadDataExport(path, conn);
        });
    }

    private void insertSyntheticDays(int days) {
        withConnection((Consumer<Connection>) conn -> insertGeneratedData(conn, days));
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
                    var value = rnd.nextInt(150_000_000);
                    var scale = (i + 1) / 3.0;
                    value = (int) (value * scale);

                    for (String metric : metrics) {
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
        withConnection(conn -> {
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
