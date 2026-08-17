package io.spoud.kcc.aggregator.ai;

import io.spoud.kcc.aggregator.olap.AggregatedMetricsRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the system prompt: the table definition plus the domain rules that separate a correct
 * answer from a confidently wrong one.
 * <p>
 * The prompt is intentionally stable across turns within a conversation so it can be
 * prompt-cached. Live introspection (metric names, context keys) is included because it is
 * small and slow-changing; the high-cardinality part — the <em>values</em> behind each context
 * key — is left to the {@code list_context_values} tool rather than inlined here.
 */
@ApplicationScoped
public class SchemaDescriber {

    private final AggregatedMetricsRepository repository;
    private final AiConfigProperties aiConfig;

    public SchemaDescriber(AggregatedMetricsRepository repository, AiConfigProperties aiConfig) {
        this.repository = repository;
        this.aiConfig = aiConfig;
    }

    public String buildSystemPrompt() {
        Set<String> metrics = repository.getAllMetrics();
        Set<String> contextKeys = repository.getAllContextKeys();

        return """
                You are the analytics assistant for Kafka Cost Control, a system that attributes the \
                cost of Kafka usage to the teams, applications and topics that caused it. You answer \
                questions about the data by querying an embedded DuckDB database.

                # The one table

                ```sql
                CREATE TABLE aggregated_data (
                    start_time          TIMESTAMPTZ NOT NULL, -- window start, inclusive, UTC
                    end_time            TIMESTAMPTZ NOT NULL, -- window end, exclusive, UTC
                    initial_metric_name VARCHAR     NOT NULL, -- e.g. confluent_kafka_server_retained_bytes
                    entity_type         VARCHAR     NOT NULL, -- 'TOPIC' | 'PRINCIPAL' | 'UNKNOWN'
                    name                VARCHAR     NOT NULL, -- topic name, or principal id
                    tags                JSON        NOT NULL, -- ALWAYS EMPTY. Never use.
                    context             JSON        NOT NULL, -- the business dimensions. Use this.
                    value               DOUBLE      NOT NULL, -- accumulated metric value for the window
                    target              VARCHAR     NOT NULL, -- context['topic'], else 'unknown'
                    id                  VARCHAR PRIMARY KEY
                );
                ```

                One row = one (1-hour window x metric x entity_type x name x distinct context map).
                Windows are one hour wide.

                # Rules you must follow

                1. **`tags` is always `{}`.** It is emptied during aggregation. Group by `context`, never `tags`.

                2. **There is no cost column.** This table stores usage, not money. For any question \
                about cost, spend, price, budget or "how much did X cost", call the `cost_overview` \
                tool. Never try to compute money in SQL. If the user asks about cost without giving \
                you an amount to distribute, ask them for the total spend for the period first.

                3. **`confluent_kafka_server_retained_bytes` is a storage gauge, aggregated with MAX, \
                not SUM.** Each row is the retained bytes at that hour, not bytes added that hour. \
                Summing it across windows massively overstates storage. Use MAX or AVG over time, \
                and only SUM across *different* entities within the same window.

                4. **Do not sum across `entity_type` values.** A TOPIC metric can be split into \
                several PRINCIPAL rows carrying the same underlying bytes, so mixing them double-counts. \
                Filter to one `entity_type` unless the user explicitly wants both.

                5. **`entity_type = 'UNKNOWN'` with an empty `name`** marks metrics that matched no \
                topic or principal rule. They are unattributable; mention them rather than silently \
                folding them into a total.

                6. **Units depend on the metric.** `*_bytes` and `kafka_log_log_size` are bytes; \
                `*_count` and `*_records` are counts; `*_cluster_load_percent` is a percentage. \
                Format bytes readably (KB/MB/GB) in your answer.

                # DuckDB idioms that work here

                - Read a context value: `context->>'application'` or `json_value(context, 'application')`
                - List keys in a row: `json_keys(context)`
                - Distinct keys across the table: `SELECT DISTINCT unnest(json_keys(context)) FROM aggregated_data`
                - Bucket by time: `time_bucket(INTERVAL 1 DAY, start_time)`
                - Missing context values are SQL NULL; use `coalesce(context->>'k', 'unknown')` when grouping.

                # How to work

                Do not guess at dimension names or values. The `context` keys are specific to this \
                installation. Before writing a query that filters or groups on a context value, call \
                `list_context_values` for that key so you use the exact spelling that exists.

                Query with `run_sql`. It is strictly read-only: only a single SELECT or WITH statement, \
                no writes, no file or network access. Always aggregate in SQL rather than pulling raw \
                rows — results are capped at %d rows.

                When you have the answer, state it directly and concisely in plain prose. Lead with the \
                number or finding the user asked for; put caveats after. Do not describe the SQL you \
                wrote — the user can already see it. If the data does not answer the question, say so \
                plainly instead of approximating.

                # What is in this installation right now

                Metrics present (%d):
                %s

                Context keys present (%d):
                %s
                """
                .formatted(
                        aiConfig.maxRows(),
                        metrics.size(), bulletList(metrics),
                        contextKeys.size(), bulletList(contextKeys));
    }

    private String bulletList(Set<String> values) {
        if (values.isEmpty()) {
            return "  (none — the database is empty. Say so rather than inventing an answer.)";
        }
        return values.stream()
                .sorted()
                .map(v -> "  - " + v)
                .collect(Collectors.joining("\n"));
    }

    /** Tool declarations offered alongside the prompt above. */
    public List<LlmTool> tools() {
        return List.of(
                LlmTool.noArgs("list_metrics",
                        "List every distinct initial_metric_name present in the database. "
                                + "Call this when you need to know which metrics exist before filtering on one."),

                LlmTool.noArgs("list_context_keys",
                        "List every key present in the JSON `context` column. These are the business "
                                + "dimensions (team, application, topic, cost-unit, ...) available for grouping. "
                                + "Call this first when the user asks about a dimension and you do not yet know "
                                + "which keys exist."),

                new LlmTool("list_context_values",
                        "List the distinct values stored under one `context` key. Call this before filtering "
                                + "or grouping on a specific value, so that you use the exact spelling that exists "
                                + "in the data rather than guessing.",
                        java.util.Map.of("key", LlmTool.stringParam(
                                "The context key to inspect, as returned by list_context_keys.")),
                        List.of("key")),

                new LlmTool("run_sql",
                        "Run a read-only SQL query against the aggregated_data table and get the rows back. "
                                + "Only a single SELECT or WITH statement is permitted; writes, DDL, and file or "
                                + "network access are rejected. Aggregate in SQL rather than fetching raw rows.",
                        java.util.Map.of("sql", LlmTool.stringParam(
                                "The DuckDB SQL query to execute.")),
                        List.of("sql")),

                new LlmTool("cost_overview",
                        "Attribute a known amount of spend across context dimensions for a time period. "
                                + "This is the ONLY correct way to answer questions about cost or money, because "
                                + "the table stores usage, not cost. It distributes the cents you supply in "
                                + "proportion to each group's share of the relevant metric. Supply whichever of the "
                                + "three cent amounts the user has told you; omit the others.",
                        java.util.Map.of(
                                "from", LlmTool.stringParam(
                                        "Start of the period, ISO-8601 instant, e.g. 2026-07-01T00:00:00Z."),
                                "to", LlmTool.stringParam(
                                        "End of the period, ISO-8601 instant. Omit for 'up to now'."),
                                "storageCents", LlmTool.integerParam(
                                        "Total storage spend for the period, in cents."),
                                "networkReadCents", LlmTool.integerParam(
                                        "Total network read (ingress) spend for the period, in cents."),
                                "networkWriteCents", LlmTool.integerParam(
                                        "Total network write (egress) spend for the period, in cents."),
                                "groupBy", LlmTool.stringArrayParam(
                                        "Context keys to break the cost down by, outermost first, "
                                                + "e.g. [\"application\"] or [\"cost-unit\", \"topic\"].")),
                        List.of("from", "groupBy")));
    }
}
