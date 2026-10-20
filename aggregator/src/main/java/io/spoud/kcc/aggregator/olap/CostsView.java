package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import io.spoud.kcc.aggregator.bills.BillEntity;
import io.spoud.kcc.aggregator.bills.BillLineEntity;
import io.spoud.kcc.aggregator.bills.BillsRepository;
import io.spoud.kcc.aggregator.repository.PricingRulesStreamRepository;
import io.spoud.kcc.data.PricingRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The view {@code costs}: every stored row with what it costs, computed when asked from today's
 * pricing rules (with their dates) and bills, so a correction applies at once and nothing needs
 * reprocessing for prices. Every place that shows a cost reads it.
 * <p>
 * Columns are those of {@code aggregated_data} - {@code cost} now meaning the cost to use - plus
 * {@code rate_cost} (the pricing rule's), {@code estimated} (the cost comes from a pricing rule, no
 * bill applies) and {@code shared} (assigned to no one, shown as {@code <shared>}). Rows:
 * <ul>
 *     <li>usage: in a billed hour, the share of the METRICS line its metric is on, by usage of the
 *     line's metrics in the month up to where the bill stops; a metric that isn't on the bill costs
 *     nothing there (it's in the bill's other lines); otherwise - no bill, past a month-to-date bill's
 *     end, or a line without an amount - the pricing rule's cost, estimated;</li>
 *     <li>{@code other}, spread by usage: the bill's USAGE lines, on each billed row in proportion to its
 *     share of the bill's billed costs;</li>
 *     <li>{@code other}, by hour: the bill's CONTEXT and SHARED lines, USAGE lines of a bill with no
 *     billed usage, and METRICS lines with no usage measured, spread evenly over the hours the bill
 *     covers.</li>
 * </ul>
 * A bill applies to the rows of its month, and for a cluster's bill only to those whose context
 * names that cluster; its rows under {@code other} carry the cluster in their context.
 * The definition is rebuilt from the rules and bills when they change; it embeds them, so a query
 * needs nothing else.
 */
@ApplicationScoped
public class CostsView {

    public static final String NAME = "costs";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A rule's price over a period: {@code baseCost + costFactor * value}; null ends are open. */
    public record RatePeriod(String metric, Instant validFrom, Instant validUntil, double baseCost, double costFactor) {
    }

    private final OlapInfra olapInfra;
    private final Instance<PricingRulesStreamRepository> pricingRules;
    private final Instance<BillsRepository> bills;
    private final String clusterKey;
    private volatile String defined;

    public CostsView(OlapInfra olapInfra, Instance<PricingRulesStreamRepository> pricingRules,
                     Instance<BillsRepository> bills, CostControlConfigProperties config) {
        this.olapInfra = olapInfra;
        this.pricingRules = pricingRules;
        this.bills = bills;
        this.clusterKey = config.cluster().contextKey();
    }

    /** Rebuilds the view from today's rules and bills if they changed; runs regularly, and after a save. */
    @Scheduled(every = "10s", delayed = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void refresh() {
        try {
            var rates = pricingRules.get().getRawPricingRules().stream()
                    .flatMap(rule -> periodsOf(rule).stream())
                    .toList();
            define(rates, bills.get().all());
        } catch (RuntimeException e) {
            // e.g. the stores or the bills topic are still loading: keep the view as it was
            Log.debugv("Costs view not refreshed: {0}", e.getMessage());
        }
    }

    /** Creates or replaces the view, unless it is already defined so. */
    public void define(Collection<RatePeriod> rates, Collection<BillEntity> savedBills) {
        String sql = sql(rates, savedBills, clusterKey);
        if (sql.equals(defined)) {
            return;
        }
        if (create(olapInfra, sql)) {
            defined = sql;
        }
    }

    /** The view with no rules and no bills, so it exists from the start. */
    static void createEmpty(OlapInfra infra) {
        create(infra, sql(List.of(), List.of(), "cluster"));
    }

    /** The view's {@code cost} column, which the table doesn't have, on {@code view} read under its alias. */
    public static Field<Double> cost(Table<?> view) {
        return DSL.field(DSL.name(view.getName(), "cost"), Double.class);
    }

    private static boolean create(OlapInfra infra, String sql) {
        return infra.getConnection().map(conn -> {
            try (var statement = conn.createStatement()) {
                statement.execute(sql);
                return true;
            } catch (SQLException e) {
                Log.errorv(e, "Could not define the costs view");
                return false;
            }
        }).orElse(false);
    }

    public static List<RatePeriod> periodsOf(PricingRule rule) {
        var periods = new ArrayList<RatePeriod>();
        rule.getEarlierPrices().forEach(p -> periods.add(new RatePeriod(rule.getMetricName(), p.getValidFrom(),
                p.getValidUntil(), p.getBaseCost(), p.getCostFactor())));
        periods.add(new RatePeriod(rule.getMetricName(), rule.getValidFrom(), null, rule.getBaseCost(), rule.getCostFactor()));
        return periods;
    }

    static String sql(Collection<RatePeriod> rates, Collection<BillEntity> savedBills, String clusterKey) {
        var bills = usable(savedBills);
        String rateRows = rates.stream()
                .map(r -> "(%s, %s, %s, %s, %s)".formatted(str(r.metric()), ts(r.validFrom()), ts(r.validUntil()),
                        num(r.baseCost()), num(r.costFactor())))
                .collect(Collectors.joining(", "));
        String billRows = bills.stream()
                .map(b -> "(%s, %s, %s, %s)".formatted(str(b.key()), month(b.month()),
                        b.cluster() == null ? "CAST(NULL AS VARCHAR)" : str(b.cluster()), ts(BillEntity.billedUntil(b))))
                .collect(Collectors.joining(", "));
        var metricLineRows = new ArrayList<String>();
        var lineMetricRows = new ArrayList<String>();
        var otherRows = new ArrayList<String>();
        for (BillEntity b : bills) {
            var seen = new HashSet<String>();
            for (int i = 0; i < b.lines().size(); i++) {
                BillLineEntity line = b.lines().get(i);
                String context = str(contextJson(b, line, clusterKey));
                if (line.allocation() == BillLineEntity.Allocation.METRICS) {
                    metricLineRows.add("(%s, %d, %s, %s, %s)".formatted(str(b.key()), i, str(line.description()),
                            line.amount() == null ? "CAST(NULL AS DOUBLE)" : num(line.amount()), context));
                    for (String metric : line.metrics()) {
                        if (seen.add(metric)) { // a metric shares one line; the save refuses a second
                            lineMetricRows.add("(%s, %d, %s)".formatted(str(b.key()), i, str(metric)));
                        }
                    }
                } else {
                    otherRows.add("(%s, %s, %s, %s, %s)".formatted(str(b.key()), str(line.allocation().name()),
                            str(line.description()), num(line.amount()), context));
                }
            }
        }

        return """
                CREATE OR REPLACE VIEW %s AS
                WITH rates AS (%s),
                bills AS (%s),
                metric_lines AS (%s),
                line_metrics AS (%s),
                other_lines AS (%s),
                base AS (
                    SELECT a.start_time, a.end_time, a.initial_metric_name, a.entity_type, a.name, a.tags, a.context,
                           a.value, a.target, a.id,
                           r.base_cost + r.cost_factor * a.value AS rate_cost,
                           b.bill_id,
                           b.bill_id IS NOT NULL AS month_billed,
                           b.bill_id IS NOT NULL AND a.start_time < b.billed_until AS in_bill,
                           lm.line_no,
                           ml.amount AS line_amount,
                           lm.metric IS NOT NULL AS bill_metric
                    FROM aggregated_data a
                    LEFT JOIN rates r ON r.metric = a.initial_metric_name
                        AND (r.valid_from IS NULL OR a.start_time >= r.valid_from)
                        AND (r.valid_until IS NULL OR a.start_time < r.valid_until)
                    LEFT JOIN bills b ON b.month_start = date_trunc('month', timezone('UTC', a.start_time))
                        AND (b.cluster IS NULL OR b.cluster = (a.context->>%s))
                    LEFT JOIN line_metrics lm ON lm.bill_id = b.bill_id AND lm.metric = a.initial_metric_name
                    LEFT JOIN metric_lines ml ON ml.bill_id = lm.bill_id AND ml.line_no = lm.line_no
                ),
                usage AS (
                    SELECT *,
                           CASE WHEN in_bill AND line_amount IS NOT NULL
                                THEN coalesce(line_amount * value / NULLIF(sum(value) FILTER (WHERE in_bill)
                                     OVER (PARTITION BY bill_id, line_no), 0), 0)
                           END AS billed_cost
                    FROM base
                ),
                usage_rows AS (
                    SELECT start_time, end_time, initial_metric_name, entity_type, name, tags, context, value, target, id,
                           false AS shared, rate_cost,
                           CASE WHEN billed_cost IS NOT NULL THEN billed_cost
                                WHEN month_billed AND NOT bill_metric THEN NULL
                                ELSE rate_cost END AS cost,
                           billed_cost IS NULL AND NOT (month_billed AND NOT bill_metric) AND rate_cost IS NOT NULL AS estimated
                    FROM usage
                ),
                billed_total AS (
                    SELECT bill_id, sum(billed_cost) AS total FROM usage WHERE billed_cost IS NOT NULL GROUP BY bill_id
                ),
                overhead AS (
                    SELECT bill_id, sum(amount) AS amount FROM other_lines WHERE kind = 'USAGE' GROUP BY bill_id
                ),
                overhead_rows AS (
                    SELECT u.start_time, u.end_time, 'other' AS initial_metric_name, u.entity_type, u.name, u.tags,
                           u.context, 0.0 AS value, '' AS target, u.id || '-overhead' AS id, false AS shared,
                           CAST(NULL AS DOUBLE) AS rate_cost, o.amount * u.billed_cost / t.total AS cost, false AS estimated
                    FROM usage u
                    JOIN overhead o ON o.bill_id = u.bill_id
                    JOIN billed_total t ON t.bill_id = u.bill_id
                    WHERE u.billed_cost IS NOT NULL AND t.total <> 0
                ),
                spread_items AS (
                    SELECT o.bill_id, b.month_start, b.billed_until, o.description AS name, o.amount, o.context,
                           o.kind <> 'CONTEXT' AS shared
                    FROM other_lines o JOIN bills b ON b.bill_id = o.bill_id
                    WHERE o.kind <> 'USAGE'
                       OR NOT EXISTS (SELECT 1 FROM billed_total t WHERE t.bill_id = o.bill_id AND t.total <> 0)
                    UNION ALL
                    SELECT ml.bill_id, b.month_start, b.billed_until, ml.description AS name, ml.amount, ml.context,
                           true AS shared
                    FROM metric_lines ml JOIN bills b ON b.bill_id = ml.bill_id
                    WHERE ml.amount IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM usage u WHERE u.bill_id = ml.bill_id AND u.line_no = ml.line_no
                          AND u.in_bill AND u.value <> 0)
                ),
                spread_rows AS (
                    SELECT h.hour AS start_time, h.hour + INTERVAL 1 HOUR AS end_time, 'other' AS initial_metric_name,
                           'OTHER' AS entity_type, s.name, CAST('{}' AS JSON) AS tags, CAST(s.context AS JSON) AS context,
                           0.0 AS value, '' AS target, md5(s.bill_id || s.name || CAST(h.hour AS VARCHAR)) AS id, s.shared,
                           CAST(NULL AS DOUBLE) AS rate_cost,
                           s.amount * 3600 / (epoch(s.billed_until) - epoch(timezone('UTC', s.month_start))) AS cost,
                           false AS estimated
                    FROM spread_items s,
                         LATERAL (SELECT unnest(generate_series(timezone('UTC', s.month_start),
                                  s.billed_until - INTERVAL 1 HOUR, INTERVAL 1 HOUR)) AS hour) h
                )
                SELECT * FROM usage_rows
                UNION ALL SELECT * FROM overhead_rows
                UNION ALL SELECT * FROM spread_rows
                """.formatted(NAME,
                rateRows.isEmpty()
                        ? "SELECT CAST(NULL AS VARCHAR) AS metric, CAST(NULL AS TIMESTAMPTZ) AS valid_from, CAST(NULL AS TIMESTAMPTZ) AS valid_until, CAST(NULL AS DOUBLE) AS base_cost, CAST(NULL AS DOUBLE) AS cost_factor WHERE false"
                        : "SELECT * FROM (VALUES " + rateRows + ") AS t(metric, valid_from, valid_until, base_cost, cost_factor)",
                billRows.isEmpty()
                        ? "SELECT CAST(NULL AS VARCHAR) AS bill_id, CAST(NULL AS TIMESTAMP) AS month_start, CAST(NULL AS VARCHAR) AS cluster, CAST(NULL AS TIMESTAMPTZ) AS billed_until WHERE false"
                        : "SELECT * FROM (VALUES " + billRows + ") AS t(bill_id, month_start, cluster, billed_until)",
                metricLineRows.isEmpty()
                        ? "SELECT CAST(NULL AS VARCHAR) AS bill_id, CAST(NULL AS INTEGER) AS line_no, CAST(NULL AS VARCHAR) AS description, CAST(NULL AS DOUBLE) AS amount, CAST(NULL AS VARCHAR) AS context WHERE false"
                        : "SELECT * FROM (VALUES " + String.join(", ", metricLineRows) + ") AS t(bill_id, line_no, description, amount, context)",
                lineMetricRows.isEmpty()
                        ? "SELECT CAST(NULL AS VARCHAR) AS bill_id, CAST(NULL AS INTEGER) AS line_no, CAST(NULL AS VARCHAR) AS metric WHERE false"
                        : "SELECT * FROM (VALUES " + String.join(", ", lineMetricRows) + ") AS t(bill_id, line_no, metric)",
                otherRows.isEmpty()
                        ? "SELECT CAST(NULL AS VARCHAR) AS bill_id, CAST(NULL AS VARCHAR) AS kind, CAST(NULL AS VARCHAR) AS description, CAST(NULL AS DOUBLE) AS amount, CAST(NULL AS VARCHAR) AS context WHERE false"
                        : "SELECT * FROM (VALUES " + String.join(", ", otherRows) + ") AS t(bill_id, kind, description, amount, context)",
                str(clusterKey));
    }

    /**
     * The bills the view uses: a month with a bill for all clusters ignores bills for single clusters
     * there, which the save refuses but the topic could still hold.
     */
    private static List<BillEntity> usable(Collection<BillEntity> savedBills) {
        var bills = savedBills.stream().filter(Objects::nonNull).toList();
        var monthsForAll = bills.stream().filter(b -> b.cluster() == null).map(BillEntity::month).collect(Collectors.toSet());
        return bills.stream().filter(b -> b.cluster() == null || !monthsForAll.contains(b.month())).toList();
    }

    /**
     * The context of a line's rows under {@code other}: a CONTEXT line's own, and a cluster's bill's
     * cluster, so its shared lines show under their cluster too.
     */
    private static String contextJson(BillEntity bill, BillLineEntity line, String clusterKey) {
        var context = new TreeMap<String, String>();
        if (bill.cluster() != null) {
            context.put(clusterKey, bill.cluster());
        }
        if (line.allocation() == BillLineEntity.Allocation.CONTEXT && line.context() != null) {
            context.putAll(line.context());
        }
        try {
            return JSON.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String num(double value) {
        return "CAST(" + Double.toString(value).toUpperCase(Locale.ROOT) + " AS DOUBLE)";
    }

    private static String ts(Instant time) {
        return time == null ? "CAST(NULL AS TIMESTAMPTZ)" : "CAST('" + time + "' AS TIMESTAMPTZ)";
    }

    private static String month(String yearMonth) {
        return "CAST('" + YearMonth.parse(yearMonth).atDay(1) + " 00:00:00' AS TIMESTAMP)";
    }
}
