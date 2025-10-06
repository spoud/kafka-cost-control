package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@ApplicationScoped
public class OlapInfra {

    private final OlapConfigProperties olapConfig;
    private final MetricNameRepository metricNameRepository;

    private Connection connection;

    public OlapInfra(OlapConfigProperties olapConfig, MetricNameRepository metricNameRepository) {
        this.olapConfig = olapConfig;
        this.metricNameRepository = metricNameRepository;
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

    public Optional<Connection> getConnection() {
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
        URL resource = Thread.currentThread().getContextClassLoader().getResource("olap-schema.sql");
        try (var statement = connection.createStatement()) {
            String sql = Files.readString(Path.of(resource.toURI()));
            statement.execute(sql);
            Log.infof("Created OLAP DB table: aggregated_data");
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
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
        getConnection().ifPresent((conn) -> loadDataExport(path, conn));
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

        var metrics = List.of("confluent_kafka_server_retained_bytes", "confluent_kafka_server_received_bytes", "confluent_kafka_server_sent_bytes");
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
        try (var statement = conn.prepareStatement("COPY aggregated_data FROM '" + path + "'")) {
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
