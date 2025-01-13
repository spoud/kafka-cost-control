package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.data.EntityType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class AggregatedMetricsRepository {
    @Inject
    OlapConfigProperties olapConfig;

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
                    "cost_center VARCHAR NOT NULL, " +
                    "PRIMARY KEY (start_time, end_time, entity_type, initial_metric_name, name, cost_center))");
        }
    }

    @Scheduled(every = "${cc.olap.database.flush-interval.seconds}s")
    public void flushToDb() {
        if (rowBuffer.isEmpty())
            return;
        getConnection().ifPresent((conn) -> {
            var skipped = 0;
            var count = 0;
            var startTime = Instant.now();
            try (var stmt = conn.prepareStatement("INSERT OR REPLACE INTO " + olapConfig.fqTableName() + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
                    stmt.setTimestamp(1, start);
                    stmt.setTimestamp(2, end);
                    stmt.setString(3, metric.getInitialMetricName());
                    stmt.setString(4, metric.getEntityType().name());
                    stmt.setString(5, metric.getName());
                    stmt.setString(6, tags);
                    stmt.setString(7, context);
                    stmt.setDouble(8, metric.getValue());
                    stmt.setString(9, contextToContextKey(metric.getContext()));
                    stmt.addBatch();
                    count++;
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                Log.error("Failed to ingest ALL metrics to OLAP database", e);
                return;
            }
            Log.infof("Ingested %d metrics. Skipped %d metrics. Duration: %s", count, skipped, Duration.between(startTime, Instant.now()));
        });
    }

    // get the value of a key from the context, respecting configured fallbacks (if any)
    private String getContextKeyValue(String key, Map<String, String> context) {
        if (olapConfig.costCenterKeys().isEmpty() || !olapConfig.costCenterKeys().get().contains(key)) {
            return context.get(key);
        }
        return context.getOrDefault(key, olapConfig.keySpecificFallback().getOrDefault(key, olapConfig.globalContextValueFallback()));
    }

    private String contextToContextKey(Map<String, String> context) {
        return olapConfig.costCenterKeys()
                .orElse(Set.of())
                .stream()
                .sorted()
                .map(key -> context.getOrDefault(key, olapConfig.keySpecificFallback().get(key)))
                .map(value -> value == null ? olapConfig.globalContextValueFallback() : value)
                .collect(Collectors.joining("|"));
    }

    private List<AggregatedDataWindowed> splitByContext(AggregatedDataWindowed metric) {
        if (olapConfig.splitBy().isEmpty())
            return List.of(metric);

        var splitMetrics = new ArrayList<AggregatedDataWindowed>();
        for (var key : olapConfig.splitBy().get()) {
            var values = Optional.ofNullable(getContextKeyValue(key, metric.getContext()))
                    .stream()
                    .map(v -> v.split(","))
                    .flatMap(Arrays::stream)
                    .distinct()
                    .toList();
            for (var value : values) {
                var newContext = new HashMap<>(metric.getContext());
                olapConfig.splitBy().get().forEach(newContext::remove);
                newContext.put(key, value);
                splitMetrics.add(AggregatedDataWindowed.newBuilder(metric)
                        .setContext(newContext)
                        .setValue(metric.getValue() / values.size())
                        .build());
            }
        }
        return splitMetrics;
    }

    public void insertRow(AggregatedDataWindowed row) {
        if (!olapConfig.enabled()) {
            return;
        }
        if (olapConfig.dropUnknownEntities() && row.getEntityType() == EntityType.UNKNOWN) {
            Log.debug("Skipping metric with unknown entity type");
            return;
        }
        splitByContext(row).forEach(this::insertSingleRow);
    }

    private void insertSingleRow(AggregatedDataWindowed row) {
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
                    try (var statement = conn.prepareStatement("SELECT DISTINCT json_keys( " + column + " ) FROM " + olapConfig.fqTableName())) {
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

    public List<AggregatedDataWindowed> getAggregatedMetric(String metricName,
                                                            AggregationType aggType,
                                                            FilterSpec tagFilter,
                                                            TimestampParam startTimestamp,
                                                            TimestampParam endTimestamp,
                                                            GroupBySpec groupBy,
                                                            Integer limit,
                                                            Integer offset,
                                                            SortOrder sort) {
        return getConnection()
                .map(conn -> {
                    try {
                        var params = new ArrayList<>();
                        var query = new StringBuilder("SELECT ");

                        var selected = groupBy.toSelectColumns().stream().collect(Collectors.joining(", ", " ", " "));
                        if (!selected.isBlank()) {
                            query.append(selected).append(", ");
                        }

                        switch (aggType) {
                            case AVG -> query.append("AVG(value) as value");
                            case SUM -> query.append("SUM(value) as value");
                            case COUNT -> query.append("COUNT(value) as value");
                            case MIN -> query.append("MIN(value) as value");
                            case MAX -> query.append("MAX(value) as value");
                        }
                        query.append(" FROM ").append(olapConfig.fqTableName()).append(" WHERE initial_metric_name = ?");
                        params.add(metricName);
                        if (startTimestamp != null) {
                            query.append(" AND start_time >= ?");
                            params.add(Timestamp.valueOf(startTimestamp.timestamp()));
                        }
                        if (endTimestamp != null) {
                            query.append(" AND end_time <= ?");
                            params.add(Timestamp.valueOf(endTimestamp.timestamp()));
                        }
                        for (var filter : tagFilter.tagFilters()) {
                            query.append(String.format(" AND (tags->>'%s' = ?)", filter.key()));
                            params.add(filter.value());
                        }
                        for (var filter : tagFilter.contextFilters()) {
                            query.append(String.format(" AND (context->>'%s' = ?)", filter.key()));
                            params.add(filter.value());
                        }
                        query.append(groupBy.toGroupByString());
                        if (sort != null) {
                            query.append(" ORDER BY value ");
                            query.append(sort);
                        }
                        if (limit != null) {
                            query.append(" LIMIT ?");
                            params.add(limit);
                        }
                        if (offset != null) {
                            query.append(" OFFSET ?");
                            params.add(offset);
                        }
                        try (var statement = conn.prepareStatement(query.toString())) {
                            for (int i = 0; i < params.size(); i++) {
                                statement.setObject(i + 1, params.get(i));
                            }
                            var result = statement.executeQuery();
                            var metrics = new ArrayList<AggregatedDataWindowed>();
                            var startTime = startTimestamp != null ? startTimestamp.timestamp().atZone(ZoneId.systemDefault()).toInstant() : Instant.EPOCH;
                            var endTime = endTimestamp != null ? endTimestamp.timestamp().atZone(ZoneId.systemDefault()).toInstant() : Instant.now();
                            while (result.next()) {
                                var aggValue = result.getDouble("value");
                                var name = groupBy.groupByResourceName() ? result.getString("name") : "unknown";
                                var tags = new HashMap<String, String>();
                                var context = new HashMap<String, String>();
                                for (var tag : groupBy.tags()) {
                                    tags.put(tag, result.getString("tag:" + tag));
                                }
                                for (var ctx : groupBy.contexts()) {
                                    context.put(ctx, result.getString("ctx:" + ctx));
                                }
                                if (groupBy.groupByStartTime()) {
                                    startTime = result.getTimestamp("start_time").toInstant();
                                }
                                if (groupBy.groupByEndTime()) {
                                    endTime = result.getTimestamp("end_time").toInstant();
                                }
                                metrics.add(AggregatedDataWindowed.newBuilder()
                                        .setContext(context)
                                        .setTags(tags)
                                        .setInitialMetricName(metricName)
                                        .setName(name)
                                        .setStartTime(startTime)
                                        .setEndTime(endTime)
                                        .setValue(aggValue)
                                        .build());
                            }
                            return metrics;
                        }
                    } catch (SQLException e) {
                        Log.error("Failed to get aggregated metric", e);
                    }
                    return null;
                })
                .orElse(null);
    }


    // only for testing/debugging purposes
    public String runQuery(String query) {
        return getConnection()
                .map(conn -> {
                    try (var statement = conn.prepareStatement(query)) {
                        var result = statement.executeQuery();
                        var rows = new ArrayList<Map<String, Object>>();
                        while (result.next()) {
                            var row = new HashMap<String, Object>();
                            for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                                row.put(result.getMetaData().getColumnName(i), result.getObject(i));
                            }
                            rows.add(row);
                        }
                        return OBJECT_MAPPER.writeValueAsString(rows);
                    } catch (SQLException | JsonProcessingException e) {
                        Log.error("Failed to run query", e);
                    }
                    return "";
                })
                .orElse("");
    }

    public record GroupBySpec(List<String> tags, List<String> contexts, boolean groupByResourceName,
                              boolean groupByStartTime, boolean groupByEndTime, boolean groupByCostCenter) {
        public static GroupBySpec fromString(String value) {
            var parts = value.split(",");
            var tags = new ArrayList<String>();
            var contexts = new ArrayList<String>();
            var groupByResourceName = false;
            var groupByStartTime = false;
            var groupByEndTime = false;
            var groupByCostCenter = false;
            for (var part : parts) {
                if (part.startsWith("tag:")) {
                    var tagKey = part.substring(4);
                    ensureIdentifierIsSafe(tagKey);
                    if (!tagKey.isBlank()) {
                        tags.add(tagKey);
                    }
                } else if (part.startsWith("context:")) {
                    var contextKey = part.substring(8);
                    ensureIdentifierIsSafe(contextKey);
                    if (!contextKey.isBlank()) {
                        contexts.add(contextKey);
                    }
                } else if (part.startsWith("builtin:")) {
                    var builtinKey = part.substring(8);
                    groupByResourceName = builtinKey.equals("resourceName") || groupByResourceName;
                    groupByStartTime = builtinKey.equals("startTime") || groupByStartTime;
                    groupByEndTime = builtinKey.equals("endTime") || groupByEndTime;
                    groupByCostCenter = builtinKey.equals("costCenter") || groupByCostCenter;
                }
            }
            return new GroupBySpec(tags, contexts, groupByResourceName, groupByStartTime, groupByEndTime, groupByCostCenter);
        }

        public List<String> toSelectColumns() {
            return Stream.concat(
                    tags.stream().map(k -> String.format("COALESCE(tags->>'%s', 'unknown') as \"%s\"", k, "tag:" + k)),
                    Stream.concat(
                            contexts.stream().map(k -> String.format("COALESCE(context->>'%s', 'unknown') as \"%s\"", k, "ctx:" + k)),
                            Stream.of("name", "start_time", "end_time", "cost_center")
                                    .filter(col -> (col.equals("name") && groupByResourceName)
                                            || (col.equals("start_time") && groupByStartTime)
                                            || (col.equals("end_time") && groupByEndTime)
                                            || (col.equals("cost_center") && groupByCostCenter))
                    )
            ).collect(Collectors.toList());
        }

        public String toGroupByString() {
            boolean anyBuiltins = groupByEndTime || groupByResourceName || groupByStartTime;
            boolean anyContext = !contexts.isEmpty();
            boolean anyTags = !tags.isEmpty();
            if (!anyTags && !anyContext && !anyBuiltins) {
                return "";
            }
            return Stream.concat(
                    tags.stream().map(k -> String.format("\"tag:%s\"", k)),
                    Stream.concat(
                            contexts.stream().map(k -> String.format("\"ctx:%s\"", k)),
                            Stream.of("name", "start_time", "end_time", "cost_center")
                                    .filter(col -> (col.equals("name") && groupByResourceName)
                                            || (col.equals("start_time") && groupByStartTime)
                                            || (col.equals("end_time") && groupByEndTime)
                                            || (col.equals("cost_center") && groupByCostCenter))
                    )
            ).collect(Collectors.joining(", ", " GROUP BY ", ""));
        }
    }

    public record FilterPair(String key, String value) {
    }

    public record FilterSpec(List<FilterPair> tagFilters, List<FilterPair> contextFilters) {
        // a filter spec is a comma separated list of the form type:key:value, where type is either "tag" or "context"
        public static FilterSpec fromString(String value) {
            var parts = value.split(",");
            var tagFilters = new ArrayList<FilterPair>();
            var contextFilters = new ArrayList<FilterPair>();
            for (var part : parts) {
                try {
                    var split = part.split(":");
                    var type = split[0];
                    var key = split[1];
                    var val = split[2];
                    ensureIdentifierIsSafe(key);
                    if (type.equals("tag")) {
                        tagFilters.add(new FilterPair(key, val));
                    } else if (type.equals("context")) {
                        contextFilters.add(new FilterPair(key, val));
                    } else {
                        throw new BadRequestException("Invalid filter type. Expected 'tag' or 'context'");
                    }
                } catch (Exception e) {
                    throw new BadRequestException("Invalid filter format. Expected comma-separated list of type:key:value triplets, e.g. tag:region:us-west-1,context:environment:prod. Key may only consist of numbers, letters and underscores", e);
                }
            }
            return new FilterSpec(tagFilters, contextFilters);
        }
    }

    public record TimestampParam(LocalDateTime timestamp) {
        public static TimestampParam fromString(String value) {
            try {
                return new TimestampParam(LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME));
            } catch (DateTimeParseException e) {
                throw new BadRequestException("Invalid timestamp format in start or end timestamp. Expected ISO-8601 format with an optional timezone offset, e.g. 2021-01-01T00:00:00+01:00", e);
            }
        }
    }

    public enum SortOrder {
        ASC, DESC;

        public static SortOrder fromString(String value) {
            try {
                return SortOrder.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid sort order. Expected 'asc' or 'desc'");
            }
        }
    }

    public enum AggregationType {
        AVG, SUM, COUNT, MIN, MAX;

        public static AggregationType fromString(String value) {
            return AggregationType.valueOf(value.toUpperCase());
        }
    }

    private static void ensureIdentifierIsSafe(String identifier) {
        if (!identifier.matches("^[a-zA-Z0-9_]+$")) {
            throw new BadRequestException("Invalid identifier. Expected only letters, numbers, and underscores");
        }
    }
}
