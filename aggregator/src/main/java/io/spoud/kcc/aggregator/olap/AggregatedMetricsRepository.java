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
import io.spoud.kcc.aggregator.bills.OtherLine;
import io.spoud.kcc.aggregator.graphql.data.BilledCostRequest;
import io.spoud.kcc.aggregator.graphql.data.BilledCostResponse;
import io.spoud.kcc.aggregator.graphql.data.CostOverviewRequest;
import io.spoud.kcc.aggregator.graphql.data.PricingRuleCostRequest;
import io.spoud.kcc.aggregator.graphql.data.CostOverviewResponse;
import io.spoud.kcc.aggregator.graphql.data.MetricHistoryTO;
import io.spoud.kcc.aggregator.graphql.data.TableResponse;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.olap.domain.tables.AggregatedData;
import io.spoud.kcc.olap.domain.tables.records.AggregatedDataRecord;
import io.vertx.core.impl.ConcurrentHashSet;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.graphql.NonNull;
import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.spoud.kcc.olap.domain.Tables.AGGREGATED_DATA;
import static org.jooq.impl.DSL.sum;

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
            try (var stmt = conn.prepareStatement("INSERT OR REPLACE INTO aggregated_data (start_time, end_time, initial_metric_name, entity_type, name, tags, context, value, target, id, cost) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
                    if (metric.getCost() == null) {
                        stmt.setNull(11, Types.DOUBLE);
                    } else {
                        stmt.setDouble(11, metric.getCost());
                    }
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

    // Request bytes flow client->broker (produce), which Confluent bills as network write;
    // response bytes flow broker->client (fetch), billed as network read. Partitions follow the
    // kafka-scraper's per-topic count (hourly max, so each topic's share is its partition-hours):
    // Confluent's own partition_count is per cluster and can't be split by topic.
    Map<String, Function<CostOverviewRequest, Integer>> metricToProvidedValue = Map.of(
            "confluent_kafka_server_retained_bytes", CostOverviewRequest::kafkaStorageCents,
            "confluent_kafka_server_request_bytes", CostOverviewRequest::kafkaNetworkWriteCents,
            "confluent_kafka_server_response_bytes", CostOverviewRequest::kafkaNetworkReadCents,
            "kafka_topic_partition_count", CostOverviewRequest::kafkaPartitionsCents
    );

    public @NonNull TableResponse calculateTable(CostOverviewRequest request) {
        return olapInfra.getDSLContext().map((dslContext) -> {
                    AggregatedData a = AGGREGATED_DATA.as("a");

                    Map<String, Double> metricToTotal = dslContext
                            .select(a.INITIAL_METRIC_NAME, sum(a.VALUE))
                            .from(a)
                            .where(withinWindow(a, request.from(), request.to()))
                            .groupBy(a.INITIAL_METRIC_NAME)
                            .stream()
                            .collect(Collectors.toMap(
                                    record -> record.value1(),
                                    record -> record.value2().doubleValue()
                            ));

                    List<Field<String>> contextKeys = request.contextKeysToGroupBy().stream()
                            .map(key -> DSL.field("context->>{0}", String.class, DSL.val(key)).as(key))
                            .toList();
                    List<Field<?>> combined = new ArrayList<>(contextKeys);
                    combined.add(a.INITIAL_METRIC_NAME);

                    List<TableResponse.TableEntry> entries = dslContext
                            .select(a.INITIAL_METRIC_NAME)
                            .select(contextKeys)
                            .select(sum(a.VALUE))
                            .from(a)
                            .where(withinWindow(a, request.from(), request.to()))
                            .groupBy(combined)
                            .orderBy(contextKeys)
                            .stream()
                            .map(record -> {
                                List<String> context = contextKeys.stream()
                                        .map(record::get)
                                        .map(contextValue -> Objects.requireNonNullElse(contextValue, "<unknown>"))
                                        .toList();

                                String initialMetricName = record.get(a.INITIAL_METRIC_NAME);
                                double total = record.get(sum(a.VALUE)).doubleValue();
                                Double totalForMetric = metricToTotal.get(initialMetricName);
                                return new TableResponse.TableEntry(
                                        initialMetricName,
                                        context,
                                        total,
                                        total / totalForMetric
                                );
                            }).toList();
                    return new TableResponse(entries);
                }).

                orElseGet(() -> new

                        TableResponse(List.of()));
    }

    /**
     * Per metric!
     * <p>
     * could be other context
     * context-1      context-2   percentage
     * dev              app-1       10%
     * prod             app-1       2.5%
     * dev              app-2       5%
     * null             null        30%   <-- "other"
     * ...              ...         ...
     */
    public CostOverviewResponse calculateCosts(CostOverviewRequest request) {
        List<CostOverviewResponse.MetricToDistributionMap> metricToDistributionMapList = new ArrayList<>();

        metricToProvidedValue.forEach((metricName, value) -> {
            Integer priceInCents = value.apply(request);
            if (priceInCents == null || priceInCents == 0) {
                // if we have a zero amount of costs we don't do any calculations for that metric
                return;
            }
            double totalForMetric = getTotalForMetric(request.from(), request.to(), metricName);
            if (totalForMetric == 0) {
                // this is surprising and unexpected since we have a total price (costs) associated with this metric but nothing in our metrics
                Log.warnf("No aggregated data for metric %s in range %s–%s, skipping cost distribution", metricName, request.from(), request.to());
                return;
            }

            // no context keys selected means "no grouping" - just show the metric-level total, without a further breakdown
            List<CostOverviewResponse.MetricToDistributionMap.NameToPrice> nameToPrices = request.contextKeysToGroupBy().isEmpty()
                    ? List.of()
                    : getTotalGroupedByContext(request.from(), request.to(), request.contextKeysToGroupBy(), metricName, AGGREGATED_DATA.VALUE).stream()
                            .map(aggregatedTotal -> new CostOverviewResponse.MetricToDistributionMap.NameToPrice(
                                    formatContextLabel(request.contextKeysToGroupBy(), aggregatedTotal.contextValues()),
                                    (aggregatedTotal.total() / totalForMetric) * priceInCents,
                                    aggregatedTotal.contextValues()
                            ))
                            .toList();
            metricToDistributionMapList.add(new CostOverviewResponse.MetricToDistributionMap(metricName, nameToPrices));

        });
        return new CostOverviewResponse(metricToDistributionMapList);
    }

    /**
     * Pricing-rule costs (bottom-up) per metric, distributed by context, in the same shape and
     * unit (cents) as {@link #calculateCosts}. Without grouping keys each metric has one entry
     * with no context values. Rows no pricing rule covered are left out.
     */
    public CostOverviewResponse calculatePricingRuleCosts(PricingRuleCostRequest request) {
        var from = request.from();
        var to = request.to();
        var keys = request.contextKeysToGroupBy();
        var distributions = pricedMetrics(from, to).stream()
                .map(metric -> new CostOverviewResponse.MetricToDistributionMap(metric,
                        getTotalGroupedByContext(from, to, keys, metric, AGGREGATED_DATA.COST).stream()
                                .map(total -> new CostOverviewResponse.MetricToDistributionMap.NameToPrice(
                                        formatContextLabel(keys, total.contextValues()),
                                        total.total() * 100,
                                        total.contextValues()))
                                .toList()))
                .toList();
        return new CostOverviewResponse(distributions);
    }

    /** The bill's amounts that aren't split by a metric's usage, named like the bill's field. */
    public static final String OTHER = "other";
    /** Context value of what isn't assigned to anyone; distinct from {@code <other>}, which means "no value". */
    static final String SHARED_VALUE = "<shared>";

    /**
     * Costs from the monthly bills, in cents. The range is taken month by month (UTC):
     * <ul>
     *     <li>a bill line goes to each group in proportion to its usage of the line's metric, relative to
     *     the month's usage up to where the bill stops - so a range covering part of a month gets the
     *     usage-weighted part of that month's line, and a range across months adds up the months;</li>
     *     <li>the bill's other lines and a line for a metric with no usage measured go to {@link #OTHER}, in
     *     proportion to the time of the month covered: an other line to its context, spread over the groups
     *     by their share of the month's usage-based costs, or shared ({@code <shared>}); an unmeasured line
     *     is shared;</li>
     *     <li>where no bill applies (no bill for the month, past a month-to-date bill's end, or a line the
     *     bill doesn't have) the rate card's costs are used and also reported as estimated. In a month
     *     without a bill that is every priced metric; in a billed month only the lines' metrics, since
     *     anything else the provider bills is in "other".</li>
     * </ul>
     */
    public BilledCostResponse calculateBilledCosts(BilledCostRequest request, Map<YearMonth, BillEntity> bills, Instant now) {
        List<String> keys = request.contextKeysToGroupBy();
        Instant from = request.from();
        Instant to = request.to() != null ? request.to() : now;
        var costs = new BilledCosts(keys);
        var months = new ArrayList<BilledCostResponse.MonthBilling>();

        for (YearMonth month = YearMonth.from(from.atZone(ZoneOffset.UTC));
             BillEntity.monthStart(month).isBefore(to); month = month.plusMonths(1)) {
            Instant monthStart = BillEntity.monthStart(month);
            Instant pieceStart = max(from, monthStart);
            Instant pieceEnd = min(to, BillEntity.monthEnd(month));
            if (!pieceStart.isBefore(pieceEnd)) {
                continue;
            }
            BillEntity bill = bills.get(month);
            if (bill == null) {
                pricedMetrics(pieceStart, pieceEnd).forEach(metric -> addEstimate(costs, pieceStart, pieceEnd, metric));
                months.add(new BilledCostResponse.MonthBilling(month.toString(), false, pieceStart, pieceEnd, null));
                continue;
            }
            Instant billedUntil = BillEntity.billedUntil(bill);
            Instant billedEnd = min(pieceEnd, billedUntil);
            double billedShareOfTime = pieceStart.isBefore(billedEnd)
                    ? (double) Duration.between(pieceStart, billedEnd).toMillis() / Duration.between(monthStart, billedUntil).toMillis()
                    : 0;
            // the usage-based costs of this part of the month per group, for spreading other lines
            var usageCosts = new LinkedHashMap<List<String>, Double>();
            for (BillLine line : BillLine.values()) {
                Double amount = line.amount(bill);
                if (amount == null) {
                    addEstimate(costs, pieceStart, pieceEnd, line.metric());
                    continue;
                }
                if (billedShareOfTime > 0) {
                    double monthUsage = getTotalForMetric(monthStart, billedUntil, line.metric());
                    if (monthUsage > 0) {
                        getTotalGroupedByContext(pieceStart, billedEnd, keys, line.metric(), AGGREGATED_DATA.VALUE)
                                .forEach(group -> {
                                    double cents = amount * 100 * group.total() / monthUsage;
                                    costs.add(line.metric(), group.contextValues(), cents, 0);
                                    usageCosts.merge(group.contextValues(), cents, Double::sum);
                                });
                    } else {
                        costs.add(OTHER, costs.sharedValues(), amount * 100 * billedShareOfTime, 0);
                    }
                }
                if (billedEnd.isBefore(pieceEnd)) {
                    addEstimate(costs, max(pieceStart, billedEnd), pieceEnd, line.metric());
                }
            }
            if (billedShareOfTime > 0) {
                for (OtherLine other : bill.otherLines()) {
                    addOtherLine(costs, other, other.amount() * 100 * billedShareOfTime, usageCosts);
                }
            }
            months.add(new BilledCostResponse.MonthBilling(month.toString(), true, pieceStart, pieceEnd, billedUntil));
        }
        return new BilledCostResponse(costs.toMetricCosts(), months);
    }

    private static void addOtherLine(BilledCosts costs, OtherLine line, double cents, Map<List<String>, Double> usageCosts) {
        double usageTotal = usageCosts.values().stream().mapToDouble(Double::doubleValue).sum();
        switch (line.allocation()) {
            case CONTEXT -> costs.add(OTHER, costs.keys.stream()
                    .map(key -> line.context() == null ? "<other>" : line.context().getOrDefault(key, "<other>"))
                    .toList(), cents, 0);
            case USAGE -> {
                if (usageTotal == 0) {
                    costs.add(OTHER, costs.sharedValues(), cents, 0);
                } else {
                    usageCosts.forEach((group, groupCents) -> costs.add(OTHER, group, cents * groupCents / usageTotal, 0));
                }
            }
            case SHARED -> costs.add(OTHER, costs.sharedValues(), cents, 0);
        }
    }

    private void addEstimate(BilledCosts costs, Instant from, Instant to, String metric) {
        getTotalGroupedByContext(from, to, costs.keys, metric, AGGREGATED_DATA.COST)
                .forEach(group -> costs.add(metric, group.contextValues(), group.total() * 100, group.total() * 100));
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** Sums of [price, estimated part] per metric and group. */
    private static final class BilledCosts {
        private final List<String> keys;
        private final Map<String, Map<List<String>, double[]>> byMetric = new TreeMap<>();

        BilledCosts(List<String> keys) {
            this.keys = keys;
        }

        void add(String metric, List<String> contextValues, double price, double estimated) {
            double[] sums = byMetric.computeIfAbsent(metric, m -> new LinkedHashMap<>())
                    .computeIfAbsent(contextValues, v -> new double[2]);
            sums[0] += price;
            sums[1] += estimated;
        }

        List<String> sharedValues() {
            return keys.stream().map(k -> SHARED_VALUE).toList();
        }

        List<BilledCostResponse.MetricCosts> toMetricCosts() {
            var result = new ArrayList<BilledCostResponse.MetricCosts>();
            byMetric.forEach((metric, groups) -> result.add(new BilledCostResponse.MetricCosts(metric,
                    groups.entrySet().stream()
                            .map(e -> new BilledCostResponse.Share(formatContextLabel(keys, e.getKey()),
                                    e.getKey(), e.getValue()[0], e.getValue()[1]))
                            .toList())));
            return result;
        }
    }

    private List<String> pricedMetrics(Instant startDate, @Nullable Instant endDate) {
        return olapInfra.getDSLContext().map(dslContext -> {
            AggregatedData a = AGGREGATED_DATA.as("a");
            return dslContext
                    .selectDistinct(a.INITIAL_METRIC_NAME)
                    .from(a)
                    .where(withinWindow(a, startDate, endDate)
                            .and(a.COST.isNotNull()))
                    .orderBy(a.INITIAL_METRIC_NAME)
                    .fetch(a.INITIAL_METRIC_NAME);
        }).orElse(List.of());
    }

    /** Windows starting at or after {@code from}; a missing {@code to} means no end. */
    private static Condition withinWindow(AggregatedData a, Instant from, @Nullable Instant to) {
        var condition = a.START_TIME.ge(from.atOffset(ZoneOffset.UTC));
        return to == null ? condition : condition.and(a.END_TIME.le(to.atOffset(ZoneOffset.UTC)));
    }

    private double getTotalForMetric(Instant startDate, @Nullable Instant endDate, String initialMetricName) {
        return olapInfra.getDSLContext().map(dslContext -> {
            AggregatedData a = AGGREGATED_DATA.as("a");
            Record1<BigDecimal> record = dslContext
                    .select(sum(a.VALUE))
                    .from(a)
                    .where(withinWindow(a, startDate, endDate)
                            .and(a.INITIAL_METRIC_NAME.eq(initialMetricName)))
                    .fetchOne();
            if (record == null || record.value1() == null) {
                return 0.0;
            }
            return record.value1().doubleValue();
        }).orElse(0.0);
    }

    private record AggregatedTotal(List<String> contextValues, double total) {
    }

    /**
     * Renders a grouped-by context tuple as a single label, e.g. {@code "team=platform, topic=orders"},
     * so that grouping by multiple context keys stays distinguishable in the cost distribution response
     * instead of collapsing to just the last key's value.
     */
    private static String formatContextLabel(List<String> contextKeys, List<String> contextValues) {
        return IntStream.range(0, contextValues.size())
                .mapToObj(i -> i < contextKeys.size()
                        ? contextKeys.get(i) + "=" + contextValues.get(i)
                        : contextValues.get(i))
                .collect(Collectors.joining(", "));
    }

    /** Sums {@code measure} (usage or pricing-rule cost) per combination of context values. */
    private List<AggregatedTotal> getTotalGroupedByContext(Instant startDate, @Nullable Instant endDate, List<String> contextKeysToGroupBy, String initialMetricName, Field<Double> measure) {
        List<AggregatedTotal> aggregatedTotals = new ArrayList<>();
        return olapInfra.getDSLContext().map((dslContext) -> {
            List<Field<String>> contextKeys = contextKeysToGroupBy.stream()
                    .map(key -> DSL.field("context->>{0}", String.class, DSL.val(key)).as(key))
                    .toList();

            AggregatedData a = AGGREGATED_DATA.as("a");
            Field<Double> aliasedMeasure = a.field(measure);
            var total = sum(aliasedMeasure).as("total");
            SelectSeekStepN<Record> select = dslContext
                    .select(contextKeys)
                    .select(total)
                    .from(a)
                    .where(withinWindow(a, startDate, endDate)
                            .and(a.INITIAL_METRIC_NAME.eq(initialMetricName))
                            .and(aliasedMeasure.isNotNull()))
                    .groupBy(contextKeys)
                    .orderBy(contextKeys);

            select.fetch().forEach(record -> {
                if (record.get(total) == null) {
                    return; // no grouping and no rows: one row with a null sum
                }
                List<String> values = new ArrayList<>();
                for (String contextKey : contextKeysToGroupBy) {
                    Object value = record.get(DSL.field(contextKey));
                    if (value == null) {
                        values.add("<other>");
                    } else {
                        values.add(value.toString());
                    }
                }
                aggregatedTotals.add(new AggregatedTotal(
                        values,
                        record.get(total).doubleValue()
                ));
            });
            return aggregatedTotals;
        }).orElse(Collections.emptyList());
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
            AggregatedData a = AGGREGATED_DATA.as("a");

            Condition condition = withinWindow(a, finalStartDate, finalEndDate);
            if (!metricName.isEmpty()) {
                condition = condition.and(a.INITIAL_METRIC_NAME.in(metricName));
            }

            var contextField = groupByContextKey == null ? null
                    : DSL.coalesce(DSL.jsonValue(a.CONTEXT, groupByContextKey), DSL.val("unknown")).as("context_value");
            var totalValue = DSL.sum(a.VALUE);
            var totalCost = DSL.sum(a.COST);
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
