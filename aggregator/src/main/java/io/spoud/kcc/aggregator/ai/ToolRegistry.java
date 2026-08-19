package io.spoud.kcc.aggregator.ai;

import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.graphql.data.CostOverviewRequest;
import io.spoud.kcc.aggregator.graphql.data.CostOverviewResponse;
import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes the tools declared by {@link SchemaDescriber}. Results are plain text bound for the
 * model's context, so they are formatted compactly. Failures are returned as error results, not
 * thrown, so the model can correct itself.
 */
@ApplicationScoped
public class ToolRegistry {

    /** Cap on values echoed back for one context key. */
    private static final int MAX_VALUES_LISTED = 200;

    private final AggregatedMetricsRepository repository;
    private final ReadOnlyQueryExecutor queryExecutor;

    /** SQL the model ran during the current question, surfaced to the UI for transparency. */
    private final ThreadLocal<List<String>> executedSql = ThreadLocal.withInitial(ArrayList::new);

    public ToolRegistry(AggregatedMetricsRepository repository, ReadOnlyQueryExecutor queryExecutor) {
        this.repository = repository;
        this.queryExecutor = queryExecutor;
    }

    /** Reset the per-question SQL audit trail. Call before starting a question. */
    public void beginQuestion() {
        executedSql.get().clear();
    }

    /** SQL executed while answering the current question, in order. */
    public List<String> executedSql() {
        return List.copyOf(executedSql.get());
    }

    public void endQuestion() {
        executedSql.remove();
    }

    /** A result destined for the user rather than the model — the private-mode path. */
    public record TerminalResult(List<String> columns, List<List<String>> rows, boolean truncated, String error) {
        public static TerminalResult failed(String message) {
            return new TerminalResult(List.of(), List.of(), false, message);
        }
    }

    /** Execute a terminal tool, returning data to the caller so it never enters the conversation. */
    public TerminalResult invokeTerminal(LlmMessage.ToolCall call) {
        try {
            return switch (call.name()) {
                case "run_sql" -> {
                    String sql = requireString(call, "sql");
                    var result = queryExecutor.execute(sql);
                    executedSql.get().add(result.executedSql());
                    yield new TerminalResult(result.columns(), result.rows(), result.truncated(), null);
                }
                case "cost_overview" -> {
                    var text = costOverview(call);
                    yield new TerminalResult(
                            List.of("cost breakdown"),
                            text.lines().map(List::of).map(l -> (List<String>) l).toList(),
                            false, null);
                }
                default -> TerminalResult.failed("Tool '" + call.name() + "' cannot return results directly.");
            };
        } catch (SqlGuard.RejectedException e) {
            executedSql.get().add("-- REJECTED: " + e.getMessage());
            return TerminalResult.failed("Query rejected. " + e.getMessage());
        } catch (ReadOnlyQueryExecutor.QueryFailedException | IllegalArgumentException e) {
            return TerminalResult.failed(e.getMessage());
        } catch (Exception e) {
            Log.warnf(e, "Terminal tool '%s' failed unexpectedly", call.name());
            return TerminalResult.failed(
                    "Tool failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** Dispatch one tool call. Never throws for tool-level problems; returns an error result. */
    public LlmMessage.ToolResult invoke(LlmMessage.ToolCall call) {
        try {
            return switch (call.name()) {
                case "list_metrics" -> LlmMessage.ToolResult.ok(call.id(), listMetrics());
                case "list_context_keys" -> LlmMessage.ToolResult.ok(call.id(), listContextKeys());
                case "list_context_values" -> LlmMessage.ToolResult.ok(call.id(), listContextValues(call));
                case "run_sql" -> runSql(call);
                case "cost_overview" -> LlmMessage.ToolResult.ok(call.id(), costOverview(call));
                default -> LlmMessage.ToolResult.error(call.id(), "Unknown tool: " + call.name());
            };
        } catch (IllegalArgumentException e) {
            return LlmMessage.ToolResult.error(call.id(), e.getMessage());
        } catch (Exception e) {
            Log.warnf(e, "Tool '%s' failed unexpectedly", call.name());
            return LlmMessage.ToolResult.error(call.id(),
                    "Tool failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private String listMetrics() {
        Set<String> metrics = repository.getAllMetrics();
        if (metrics.isEmpty()) {
            return "No metrics found — the database is empty.";
        }
        return metrics.stream().sorted().collect(Collectors.joining("\n"));
    }

    private String listContextKeys() {
        Set<String> keys = repository.getAllContextKeys();
        if (keys.isEmpty()) {
            return "No context keys found — either the database is empty, or no context rules have matched any metric.";
        }
        return keys.stream().sorted().collect(Collectors.joining("\n"));
    }

    private String listContextValues(LlmMessage.ToolCall call) {
        String key = requireString(call, "key");
        Set<String> values = repository.getAllContextValues(key);
        if (values.isEmpty()) {
            return "No values found for context key '" + key + "'. Call list_context_keys to see which keys exist.";
        }
        var sorted = values.stream().sorted().toList();
        if (sorted.size() > MAX_VALUES_LISTED) {
            return String.join("\n", sorted.subList(0, MAX_VALUES_LISTED))
                    + "\n... (" + (sorted.size() - MAX_VALUES_LISTED) + " more values not shown; "
                    + "there are " + sorted.size() + " distinct values in total. "
                    + "Aggregate in SQL rather than enumerating them.)";
        }
        return String.join("\n", sorted);
    }

    private LlmMessage.ToolResult runSql(LlmMessage.ToolCall call) {
        String sql = requireString(call, "sql");
        try {
            var result = queryExecutor.execute(sql);
            executedSql.get().add(result.executedSql());
            return LlmMessage.ToolResult.ok(call.id(), formatRows(result));
        } catch (SqlGuard.RejectedException e) {
            // Rejected attempts are recorded too: they are the most informative part of the trail.
            executedSql.get().add("-- REJECTED: " + e.getMessage() + "\n" + sql);
            return LlmMessage.ToolResult.error(call.id(), "Query rejected. " + e.getMessage());
        } catch (ReadOnlyQueryExecutor.QueryFailedException e) {
            executedSql.get().add("-- FAILED: " + e.getMessage() + "\n" + sql);
            return LlmMessage.ToolResult.error(call.id(), e.getMessage());
        }
    }

    /** Render a result set as TSV. */
    private String formatRows(ReadOnlyQueryExecutor.QueryResult result) {
        if (result.rows().isEmpty()) {
            return "0 rows. The query is valid but matched no data — check the time range and filter values.";
        }
        var sb = new StringBuilder();
        sb.append(String.join("\t", result.columns())).append('\n');
        for (var row : result.rows()) {
            sb.append(row.stream()
                    .map(v -> v == null ? "NULL" : v.replace('\t', ' ').replace('\n', ' '))
                    .collect(Collectors.joining("\t")));
            sb.append('\n');
        }
        sb.append("(").append(result.rowCount()).append(" rows");
        if (result.truncated()) {
            sb.append("; TRUNCATED at the row limit — aggregate further for a complete answer");
        }
        sb.append(")");
        return sb.toString();
    }

    private String costOverview(LlmMessage.ToolCall call) {
        Instant from = parseInstant(requireString(call, "from"), "from");
        Instant to = optionalString(call, "to").map(s -> parseInstant(s, "to")).orElse(null);

        List<String> groupBy = optionalStringList(call, "groupBy");
        if (groupBy.isEmpty()) {
            throw new IllegalArgumentException(
                    "groupBy must contain at least one context key. Call list_context_keys to see the options.");
        }

        Integer storage = optionalInt(call, "storageCents");
        Integer read = optionalInt(call, "networkReadCents");
        Integer write = optionalInt(call, "networkWriteCents");
        if (storage == null && read == null && write == null) {
            throw new IllegalArgumentException(
                    "At least one of storageCents, networkReadCents or networkWriteCents is required. "
                            + "The table stores usage, not cost, so an amount to distribute must be supplied. "
                            + "Ask the user what they spent for this period.");
        }

        var request = new CostOverviewRequest(from, to, null, storage, read, write, groupBy);
        CostOverviewResponse response = repository.calculateCosts(request);

        return formatCostOverview(response, groupBy);
    }

    private String formatCostOverview(CostOverviewResponse response, List<String> groupBy) {
        if (response.metricToDistributionMapList().isEmpty()) {
            return "No cost data for that period. Either there is no usage in range, or none of the "
                    + "three priced metrics (retained_bytes, request_bytes, response_bytes) are present.";
        }
        var sb = new StringBuilder();
        sb.append("Cost distribution, grouped by: ").append(String.join(", ", groupBy)).append('\n');
        sb.append("All amounts are in cents.\n\n");

        for (var metricEntry : response.metricToDistributionMapList()) {
            sb.append("## ").append(metricEntry.metric()).append('\n');
            var entries = metricEntry.nameToPriceList().stream()
                    .sorted((a, b) -> Double.compare(
                            b.price() == null ? 0 : b.price(),
                            a.price() == null ? 0 : a.price()))
                    .toList();
            for (var e : entries) {
                sb.append(e.name()).append('\t')
                        .append(e.price() == null ? "0" : String.format("%.2f", e.price()))
                        .append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // --- argument helpers -------------------------------------------------------------------

    private String requireString(LlmMessage.ToolCall call, String name) {
        Object value = call.input().get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Missing required parameter '" + name + "'.");
        }
        return String.valueOf(value);
    }

    private java.util.Optional<String> optionalString(LlmMessage.ToolCall call, String name) {
        Object value = call.input().get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(String.valueOf(value));
    }

    private Integer optionalInt(LlmMessage.ToolCall call, String name) {
        Object value = call.input().get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter '" + name + "' must be a whole number of cents.");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> optionalStringList(LlmMessage.ToolCall call, String name) {
        Object value = call.input().get(name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        if (value instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map).values().stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(value));
    }

    private Instant parseInstant(String raw, String paramName) {
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Parameter '" + paramName + "' must be an ISO-8601 instant such as 2026-07-01T00:00:00Z, got: " + raw);
        }
    }
}
