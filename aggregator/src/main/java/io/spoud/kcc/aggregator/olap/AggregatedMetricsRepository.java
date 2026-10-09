package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import io.spoud.kcc.aggregator.data.MetricNameEntity;
import io.spoud.kcc.aggregator.bills.BillEntity;
import io.spoud.kcc.aggregator.bills.BillLine;
import io.spoud.kcc.aggregator.graphql.data.BilledCostRequest;
import io.spoud.kcc.aggregator.graphql.data.BilledCostResponse;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.olap.domain.tables.AggregatedData;
import io.spoud.kcc.olap.domain.tables.records.AggregatedDataRecord;
import io.vertx.core.impl.ConcurrentHashSet;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;
import org.jooq.*;
import org.jooq.impl.DSL;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.spoud.kcc.olap.domain.Tables.AGGREGATED_DATA;

@Startup
@ApplicationScoped
public class AggregatedMetricsRepository {
    public static final TypeReference<Map<String, String>> MAP_STRING_STRING_TYPE_REF = new TypeReference<>() {
    };

    private final OlapConfigProperties olapConfig;
    private final CostControlConfigProperties costControlConfig;
    private final OlapInfra olapInfra;
    private final BlockingQueue<AggregatedDataWindowed> rowBuffer;
    private final MetricNameRepository metricNameRepository;
    private final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Set<String> contextKeys = new ConcurrentHashSet<>();

    public AggregatedMetricsRepository(OlapConfigProperties olapConfig, CostControlConfigProperties costControlConfig, OlapInfra olapInfra, MetricNameRepository metricNameRepository) {
        Log.info("Initializing AggregatedMetricsRepository");
        var startTime = Instant.now();
        this.olapConfig = olapConfig;
        this.costControlConfig = costControlConfig;
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
        if (!olapConfig.enabled()) {
            return;
        }
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

    /**
     * Deletes every stored window starting at or after {@code start}, after flushing what is still
     * buffered, so a reprocess replaces those windows instead of adding re-enriched copies next to
     * them (a row's id includes its context).
     *
     * @return the number of rows deleted
     */
    public synchronized int deleteFrom(Instant start) {
        flushToDb();
        return olapInfra.getConnection().map(conn -> {
            try (var stmt = conn.prepareStatement("DELETE FROM aggregated_data WHERE start_time >= ?")) {
                stmt.setObject(1, start.atOffset(ZoneOffset.UTC));
                int deleted = stmt.executeUpdate();
                Log.infof("Deleted %d OLAP rows starting at or after %s", deleted, start);
                return deleted;
            } catch (SQLException e) {
                throw new IllegalStateException("Could not delete OLAP rows from " + start, e);
            }
        }).orElse(0);
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
            try (var stmt = conn.prepareStatement("INSERT OR REPLACE INTO aggregated_data (start_time, end_time, initial_metric_name, entity_type, name, tags, context, value, target, id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
        try {
            rowBuffer.put(row);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
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
                    try (var statement = conn.prepareStatement("SELECT DISTINCT unnest(json_keys( " + column + " )) FROM aggregated_data")) {
                        return getStatementResultAsStrings(statement, true);
                    } catch (Exception e) {
                        Log.error("Failed to get keys of column: " + column, e);
                    }
                    return new HashSet<String>();
                })
                .orElse(new HashSet<>());
    }

    /**
     * Distinct values of one JSON key.
     * <p>
     * The key is a bind parameter, not interpolated: a context key is whatever someone typed into
     * a context-data rule, and real ones contain hyphens - {@code app-id}, {@code cost-unit}. The
     * previous form built the SQL with String.format, which forced an identifier check strict
     * enough to reject those, so the only keys this could read were the ones that happened to look
     * like identifiers. Binding removes both the injection risk and the restriction.
     */
    private Set<String> getAllJsonKeyValues(String column, String key) {
        ensureIdentifierIsSafe(column);
        return olapInfra.getConnection()
                .map(conn -> {
                    try (var statement = conn.prepareStatement(
                            "SELECT DISTINCT json_extract_string(%s, ?) FROM aggregated_data".formatted(column))) {
                        statement.setString(1, jsonPath(key));
                        return getStatementResultAsStrings(statement, false);
                    } catch (Exception e) {
                        Log.error("Failed to get keys of column: " + column, e);
                    }
                    return new HashSet<String>();
                })
                .orElse(new HashSet<>());
    }

    /**
     * A JSON path selecting exactly one top-level key, whatever it is called.
     * <p>
     * Context keys are user-authored, so they are not identifiers and nothing constrains their
     * characters. The unquoted form {@code $.key} reads punctuation structurally: {@code cost.unit}
     * becomes a nested lookup and {@code a[0]} an array index, both returning null rather than
     * failing - a silently wrong answer. Quoting the key makes it literal; the escapes are for the
     * quoted string itself, not for SQL, since the whole path is bound as a parameter.
     */
    private static String jsonPath(String key) {
        return "$.\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
            // from the costs view: each row with its cost, and the bills' other lines
            try (var statement = conn.prepareStatement("COPY (SELECT * FROM " + CostsView.NAME + " WHERE start_time >= ? AND end_time <= ?) TO '" + tmpFileName + "'"
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

    /** The bill's amounts that aren't split by a metric's usage, named like the bill's field. */
    public static final String OTHER = "other";
    /** Context value of what isn't assigned to anyone; distinct from {@code <other>}, which means "no value". */
    static final String SHARED_VALUE = "<shared>";

    /**
     * Costs in cents, from the {@link CostsView costs view}: the bills shared by usage where they apply,
     * the pricing rules (estimated) elsewhere. Grouped by metric and the requested context keys; the
     * months say for each month of the range whether a bill covers it.
     */
    public BilledCostResponse calculateBilledCosts(BilledCostRequest request, Map<YearMonth, BillEntity> bills, Instant now) {
        List<String> keys = request.contextKeysToGroupBy();
        Instant from = request.from();
        Instant to = request.to() != null ? request.to() : now;

        var months = new ArrayList<BilledCostResponse.MonthBilling>();
        for (YearMonth month = YearMonth.from(from.atZone(ZoneOffset.UTC));
             BillEntity.monthStart(month).isBefore(to); month = month.plusMonths(1)) {
            Instant pieceStart = max(from, BillEntity.monthStart(month));
            Instant pieceEnd = min(to, BillEntity.monthEnd(month));
            if (pieceStart.isBefore(pieceEnd)) {
                BillEntity bill = bills.get(month);
                months.add(new BilledCostResponse.MonthBilling(month.toString(), bill != null, pieceStart, pieceEnd,
                        bill == null ? null : BillEntity.billedUntil(bill)));
            }
        }

        var metrics = olapInfra.getDSLContext().map(dsl -> {
            AggregatedData c = costsView();
            Field<Boolean> estimated = DSL.field(DSL.name("c", "estimated"), Boolean.class);
            List<Field<String>> keyFields = contextValues(c, keys);
            var total = DSL.sum(CostsView.cost(c)).as("total");
            var estimatedTotal = DSL.sum(CostsView.cost(c)).filterWhere(estimated).as("estimated_total");
            var grouping = new ArrayList<Field<?>>();
            grouping.add(c.INITIAL_METRIC_NAME);
            grouping.addAll(keyFields);
            var byMetric = new TreeMap<String, List<BilledCostResponse.Share>>();
            dsl.select(grouping).select(total, estimatedTotal)
                    .from(c)
                    .where(withinWindow(c, from, to).and(CostsView.cost(c).isNotNull()))
                    .groupBy(grouping)
                    .orderBy(grouping)
                    .fetch()
                    .forEach(record -> {
                        var values = keyFields.stream().map(record::get).toList();
                        double price = record.get(total).doubleValue() * 100;
                        var estimatedSum = record.get(estimatedTotal);
                        byMetric.computeIfAbsent(record.get(c.INITIAL_METRIC_NAME), m -> new ArrayList<>())
                                .add(new BilledCostResponse.Share(formatContextLabel(keys, values), values, price,
                                        estimatedSum == null ? 0 : estimatedSum.doubleValue() * 100));
                    });
            return byMetric.entrySet().stream()
                    .map(e -> new BilledCostResponse.MetricCosts(e.getKey(), e.getValue()))
                    .toList();
        }).orElse(List.of());
        return new BilledCostResponse(metrics, months);
    }

    /**
     * Each bill line's metric summed over the hours the bill covers: what the {@link CostsView costs
     * view} shares the line by. Metrics without rows are missing.
     */
    public Map<String, Double> usageInBill(BillEntity bill) {
        var metrics = Arrays.stream(BillLine.values()).map(BillLine::metric).toList();
        var from = BillEntity.monthStart(YearMonth.parse(bill.month())).atOffset(ZoneOffset.UTC);
        var until = BillEntity.billedUntil(bill).atOffset(ZoneOffset.UTC);
        return olapInfra.getDSLContext().map(dsl -> {
            AggregatedData a = AGGREGATED_DATA.as("a");
            var usage = DSL.sum(a.VALUE).as("usage");
            Map<String, Double> result = new HashMap<>();
            dsl.select(a.INITIAL_METRIC_NAME, usage)
                    .from(a)
                    .where(a.INITIAL_METRIC_NAME.in(metrics)
                            .and(a.START_TIME.ge(from))
                            .and(a.START_TIME.lt(until)))
                    .groupBy(a.INITIAL_METRIC_NAME)
                    .fetch()
                    .forEach(r -> result.put(r.get(a.INITIAL_METRIC_NAME), r.get(usage).doubleValue()));
            return result;
        }).orElse(Map.of());
    }

    /** The {@link CostsView costs view}, read with the table's fields, as {@code c}. */
    static AggregatedData costsView() {
        return AGGREGATED_DATA.rename(CostsView.NAME).as("c");
    }

    /** Each grouping key's value: {@code <shared>} on what is assigned to no one, {@code <other>} where missing. */
    static List<Field<String>> contextValues(AggregatedData c, List<String> keys) {
        Field<Boolean> shared = DSL.field(DSL.name("c", "shared"), Boolean.class);
        var fields = new ArrayList<Field<String>>();
        for (int i = 0; i < keys.size(); i++) {
            // parenthesized: DuckDB would read `context->>key IS NULL` as `context->>(key IS NULL)`
            fields.add(DSL.field("CASE WHEN {0} THEN {1} ELSE coalesce(({2}->>{3}), {4}) END", String.class,
                    shared, DSL.val(SHARED_VALUE), c.CONTEXT, DSL.val(keys.get(i)), DSL.val("<other>")).as("key_" + i));
        }
        return fields;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** Windows starting at or after {@code from}; a missing {@code to} means no end. */
    private static Condition withinWindow(AggregatedData a, Instant from, @Nullable Instant to) {
        var condition = a.START_TIME.ge(from.atOffset(ZoneOffset.UTC));
        return to == null ? condition : condition.and(a.END_TIME.le(to.atOffset(ZoneOffset.UTC)));
    }

    /**
     * Renders a grouped-by context tuple as a single label, e.g. {@code "team=platform, topic=orders"},
     * so that grouping by multiple context keys stays distinguishable in the cost response
     * instead of collapsing to just the last key's value.
     */
    private static String formatContextLabel(List<String> contextKeys, List<String> contextValues) {
        return IntStream.range(0, contextValues.size())
                .mapToObj(i -> i < contextKeys.size()
                        ? contextKeys.get(i) + "=" + contextValues.get(i)
                        : contextValues.get(i))
                .collect(Collectors.joining(", "));
    }

    public List<MetricEO> getHistory(Instant startDate, Instant endDate, Set<String> metricNames) {
        return olapInfra.getConnection().map((conn) -> {
            var finalStartDate = startDate == null ? Instant.now().minus(Duration.ofDays(30)) : startDate;
            var finalEndDate = endDate == null ? Instant.now() : endDate;
            Log.infof("Generating report for the period from %s to %s", finalStartDate, finalEndDate);

            DSLContext dslContext = DSL.using(conn);
            AggregatedData a = AGGREGATED_DATA.as("a");

            Condition condition = withinWindow(a, finalStartDate, finalEndDate);
            if (!metricNames.isEmpty()) {
                condition = condition.and(a.INITIAL_METRIC_NAME.in(metricNames));
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

    /** Points per series produced by {@link #defaultBucketWidth}. */
    static final int TARGET_BUCKETS_PER_SERIES = 24;

    /**
     * Bucket width holding a series at {@link #TARGET_BUCKETS_PER_SERIES} points for any range,
     * never finer than {@code aggregationWindow}, which is the resolution the rows were written at.
     */
    static Duration defaultBucketWidth(Instant startDate, Instant endDate, Duration aggregationWindow) {
        var range = Duration.between(startDate, endDate);
        var perBucket = range.dividedBy(TARGET_BUCKETS_PER_SERIES);
        return perBucket.compareTo(aggregationWindow) > 0 ? perBucket : aggregationWindow;
    }

    /** The {@code time_bucket} expression grouping rows into {@code width} from {@code start}. */
    private static Field<OffsetDateTime> timeBucket(Duration width, Field<OffsetDateTime> startTime, Instant start) {
        var interval = DSL.field("INTERVAL %d SECOND".formatted(Math.max(1, width.toSeconds())));
        return DSL.function("time_bucket", OffsetDateTime.class, interval, startTime, DSL.val(start)).as("time_bucket");
    }

    /**
     * Get aggregated metric history, grouped by a context key, with optional grouping by time buckets within the time range.
     * Note that not grouping by time buckets is equivalent to setting the bucket width to the entire time range.
     *
     * @param startDate Start time of the query range. If null, defaults to 30 days ago.
     * @param endDate End time of the query range. If null, defaults to now.
     * @param metricName Set of metric names to filter by. If empty, includes all metrics, kept as separate series.
     * @param groupByContextKey The context key to group by, or null for no context breakdown. Must be a valid JSON key in the context field.
     * @param timeBucketWidthHours Optional width of time buckets in hours. If null, {@link #defaultBucketWidth} is used. If bucketing by time window is not desired, set it to the hours between startDate and endDate.
     * @return A collection of MetricHistoryTO objects, one per context value for a single metric,
     *         otherwise one per metric and context value pair, each containing lists of timestamps
     *         and corresponding aggregated metric values.
     */
    public Collection<MetricHistoryTO> getHistoryGrouped(@Nullable Instant startDate, @Nullable Instant endDate, Set<String> metricName, @Nullable String groupByContextKey, @Nullable Long timeBucketWidthHours) {
        return olapInfra.getConnection().map((conn) -> {
            var finalStartDate = startDate == null ? Instant.now().minus(Duration.ofDays(30)) : startDate;
            var finalEndDate = endDate == null ? Instant.now() : endDate;
            var reportStart = Instant.now();
            Log.infof("Generating report for the period from %s to %s, grouped for context '%s'", finalStartDate, finalEndDate, groupByContextKey);

            DSLContext dslContext = DSL.using(conn);
            // the costs view: values as stored, costs from today's rules and bills
            AggregatedData a = AGGREGATED_DATA.rename(CostsView.NAME).as("a");

            Condition condition = withinWindow(a, finalStartDate, finalEndDate);
            if (!metricName.isEmpty()) {
                condition = condition.and(a.INITIAL_METRIC_NAME.in(metricName));
            }

            var contextField = groupByContextKey == null ? null
                    : DSL.coalesce(DSL.jsonValue(a.CONTEXT, groupByContextKey), DSL.val("unknown")).as("context_value");
            var totalValue = DSL.sum(a.VALUE);
            var totalCost = DSL.sum(CostsView.cost(a));
            var range = Duration.between(finalStartDate, finalEndDate);
            var requested = timeBucketWidthHours == null ? null : Duration.ofHours(timeBucketWidthHours);
            var bucketWidth = requested == null
                    ? defaultBucketWidth(finalStartDate, finalEndDate, costControlConfig.aggregationWindowSize())
                    : (requested.compareTo(range) <= 0 ? requested : range);
            var tb = timeBucket(bucketWidth, a.START_TIME, finalStartDate);
            // Always grouped by metric: different metrics are different quantities
            // (see cc.metrics.aggregations) and must not be summed into one series.
            var grouping = contextField == null
                    ? new Field<?>[]{a.INITIAL_METRIC_NAME, tb}
                    : new Field<?>[]{a.INITIAL_METRIC_NAME, contextField, tb};
            var dslQuery = dslContext
                    .select(totalValue, totalCost).select(grouping)
                    .from(a)
                    .where(condition)
                    .groupBy(grouping)
                    .orderBy(grouping);

            Log.debugf("Executing query: %s", dslQuery);

            // With one metric the series name is just the context value; otherwise it carries both.
            boolean singleMetric = metricName.size() == 1;
            var fetchResult = dslQuery.fetch();
            Map<String, MetricHistoryTO> metrics = new LinkedHashMap<>();
            fetchResult.forEach(record -> {
                var metric = record.get(a.INITIAL_METRIC_NAME);
                var contextValue = contextField == null ? null : record.get(contextField).data();
                var seriesName = contextValue == null ? metric
                        : singleMetric ? contextValue : metric + " · " + contextValue;
                var metricHistory = metrics.computeIfAbsent(seriesName, k -> new MetricHistoryTO(
                        seriesName,
                        contextValue == null ? Map.of() : Map.of(groupByContextKey, contextValue),
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>()
                ));
                var startTime = record.get(tb).toInstant();
                metricHistory.getTimes().add(startTime);
                metricHistory.getValues().add(record.get(totalValue).doubleValue());
                var cost = record.get(totalCost);
                metricHistory.getCosts().add(cost == null ? null : cost.doubleValue());
            });
            Log.infof("Generated report for the period from %s to %s, grouped for context '%s' in %s.",
                    finalStartDate, finalEndDate, groupByContextKey, Duration.between(reportStart, Instant.now()));
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
