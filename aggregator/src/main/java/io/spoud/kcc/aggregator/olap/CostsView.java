package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.spoud.kcc.aggregator.bills.BillEntity;
import io.spoud.kcc.aggregator.bills.BillLine;
import io.spoud.kcc.aggregator.bills.BillsRepository;
import io.spoud.kcc.aggregator.bills.OtherLine;
import io.spoud.kcc.aggregator.repository.PricingRulesStreamRepository;
import io.spoud.kcc.data.PricingRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
 *     <li>usage: in a billed hour, a bill line's share by usage of its metric in the month up to where
 *     the bill stops; a metric that isn't one of the bill's lines costs nothing there (it's in the
 *     bill's other part); otherwise - no bill, past a month-to-date bill's end, or a line the bill
 *     leaves empty - the pricing rule's cost, estimated;</li>
 *     <li>{@code other}, spread by usage: the bill's USAGE lines, on each billed row in proportion to its
 *     share of the month's billed costs;</li>
 *     <li>{@code other}, by hour: the bill's CONTEXT and SHARED lines, USAGE lines in a month with no
 *     billed usage, and lines for a metric with no usage measured, spread evenly over the hours the
 *     bill covers.</li>
 * </ul>
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
    private volatile String defined;

    public CostsView(OlapInfra olapInfra, Instance<PricingRulesStreamRepository> pricingRules,
                     Instance<BillsRepository> bills) {
        this.olapInfra = olapInfra;
        this.pricingRules = pricingRules;
        this.bills = bills;
    }

    /** Rebuilds the view from today's rules and bills if they changed; runs regularly, and after a save. */
    @Scheduled(every = "10s", delayed = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void refresh() {
        try {
            var rates = pricingRules.get().getRawPricingRules().stream()
                    .flatMap(rule -> periodsOf(rule).stream())
                    .toList();
            define(rates, bills.get().byMonth().values());
        } catch (RuntimeException e) {
            // e.g. the stores or the bills topic are still loading: keep the view as it was
            Log.debugv("Costs view not refreshed: {0}", e.getMessage());
        }
    }

    /** Creates or replaces the view, unless it is already defined so. */
    public void define(Collection<RatePeriod> rates, Collection<BillEntity> monthBills) {
        String sql = sql(rates, monthBills);
        if (sql.equals(defined)) {
            return;
        }
        if (create(olapInfra, sql)) {
            defined = sql;
        }
    }

    /** The view with no rules and no bills, so it exists from the start. */
    static void createEmpty(OlapInfra infra) {
        create(infra, sql(List.of(), List.of()));
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

    static String sql(Collection<RatePeriod> rates, Collection<BillEntity> monthBills) {
        var bills = monthBills.stream().filter(Objects::nonNull).toList();
        String rateRows = rates.stream()
                .map(r -> "(%s, %s, %s, %s, %s)".formatted(str(r.metric()), ts(r.validFrom()), ts(r.validUntil()),
                        num(r.baseCost()), num(r.costFactor())))
                .collect(Collectors.joining(", "));
        String billRows = bills.stream()
                .map(b -> "(%s, %s)".formatted(month(b.month()), ts(BillEntity.billedUntil(b))))
                .collect(Collectors.joining(", "));
        String lineRows = bills.stream()
                .flatMap(b -> java.util.Arrays.stream(BillLine.values())
                        .filter(line -> line.amount(b) != null)
                        .map(line -> "(%s, %s, %s)".formatted(month(b.month()), str(line.metric()), num(line.amount(b)))))
                .collect(Collectors.joining(", "));
        String otherRows = bills.stream()
                .flatMap(b -> b.otherLines().stream().map(o -> "(%s, %s, %s, %s, %s)".formatted(month(b.month()),
                        str(o.allocation().name()), str(o.description()), num(o.amount()), str(contextJson(o)))))
                .collect(Collectors.joining(", "));
        String billMetrics = java.util.Arrays.stream(BillLine.values())
                .map(line -> "(" + str(line.metric()) + ")")
                .collect(Collectors.joining(", "));

        return """
                CREATE OR REPLACE VIEW %s AS
                WITH rates AS (%s),
                bills AS (%s),
                bill_lines AS (%s),
                other_lines AS (%s),
                bill_metrics(metric) AS (VALUES %s),
                base AS (
                    SELECT a.start_time, a.end_time, a.initial_metric_name, a.entity_type, a.name, a.tags, a.context,
                           a.value, a.target, a.id,
                           date_trunc('month', timezone('UTC', a.start_time)) AS month,
                           r.base_cost + r.cost_factor * a.value AS rate_cost,
                           b.billed_until IS NOT NULL AS month_billed,
                           b.billed_until IS NOT NULL AND a.start_time < b.billed_until AS in_bill,
                           bl.amount AS line_amount,
                           bm.metric IS NOT NULL AS bill_metric
                    FROM aggregated_data a
                    LEFT JOIN rates r ON r.metric = a.initial_metric_name
                        AND (r.valid_from IS NULL OR a.start_time >= r.valid_from)
                        AND (r.valid_until IS NULL OR a.start_time < r.valid_until)
                    LEFT JOIN bills b ON b.month_start = date_trunc('month', timezone('UTC', a.start_time))
                    LEFT JOIN bill_lines bl ON bl.month_start = b.month_start AND bl.metric = a.initial_metric_name
                    LEFT JOIN bill_metrics bm ON bm.metric = a.initial_metric_name
                ),
                usage AS (
                    SELECT *,
                           CASE WHEN in_bill AND line_amount IS NOT NULL
                                THEN coalesce(line_amount * value / NULLIF(sum(value) FILTER (WHERE in_bill)
                                     OVER (PARTITION BY initial_metric_name, month), 0), 0)
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
                billed_month_total AS (
                    SELECT month, sum(billed_cost) AS total FROM usage WHERE billed_cost IS NOT NULL GROUP BY month
                ),
                overhead_month AS (
                    SELECT month_start, sum(amount) AS amount FROM other_lines WHERE kind = 'USAGE' GROUP BY month_start
                ),
                overhead_rows AS (
                    SELECT u.start_time, u.end_time, 'other' AS initial_metric_name, u.entity_type, u.name, u.tags,
                           u.context, 0.0 AS value, '' AS target, u.id || '-overhead' AS id, false AS shared,
                           CAST(NULL AS DOUBLE) AS rate_cost, o.amount * u.billed_cost / t.total AS cost, false AS estimated
                    FROM usage u
                    JOIN overhead_month o ON o.month_start = u.month
                    JOIN billed_month_total t ON t.month = u.month
                    WHERE u.billed_cost IS NOT NULL AND t.total <> 0
                ),
                spread_items AS (
                    SELECT o.month_start, b.billed_until, o.description AS name, o.amount,
                           CASE WHEN o.kind = 'CONTEXT' THEN o.context ELSE '{}' END AS context,
                           o.kind <> 'CONTEXT' AS shared
                    FROM other_lines o JOIN bills b ON b.month_start = o.month_start
                    WHERE o.kind <> 'USAGE'
                       OR NOT EXISTS (SELECT 1 FROM billed_month_total t WHERE t.month = o.month_start AND t.total <> 0)
                    UNION ALL
                    SELECT bl.month_start, b.billed_until, bl.metric AS name, bl.amount, '{}' AS context, true AS shared
                    FROM bill_lines bl JOIN bills b ON b.month_start = bl.month_start
                    WHERE NOT EXISTS (SELECT 1 FROM usage u WHERE u.month = bl.month_start
                        AND u.initial_metric_name = bl.metric AND u.in_bill AND u.value <> 0)
                ),
                spread_rows AS (
                    SELECT h.hour AS start_time, h.hour + INTERVAL 1 HOUR AS end_time, 'other' AS initial_metric_name,
                           'OTHER' AS entity_type, s.name, CAST('{}' AS JSON) AS tags, CAST(s.context AS JSON) AS context,
                           0.0 AS value, '' AS target, md5(s.name || CAST(h.hour AS VARCHAR)) AS id, s.shared,
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
                        ? "SELECT CAST(NULL AS TIMESTAMP) AS month_start, CAST(NULL AS TIMESTAMPTZ) AS billed_until WHERE false"
                        : "SELECT * FROM (VALUES " + billRows + ") AS t(month_start, billed_until)",
                lineRows.isEmpty()
                        ? "SELECT CAST(NULL AS TIMESTAMP) AS month_start, CAST(NULL AS VARCHAR) AS metric, CAST(NULL AS DOUBLE) AS amount WHERE false"
                        : "SELECT * FROM (VALUES " + lineRows + ") AS t(month_start, metric, amount)",
                otherRows.isEmpty()
                        ? "SELECT CAST(NULL AS TIMESTAMP) AS month_start, CAST(NULL AS VARCHAR) AS kind, CAST(NULL AS VARCHAR) AS description, CAST(NULL AS DOUBLE) AS amount, CAST(NULL AS VARCHAR) AS context WHERE false"
                        : "SELECT * FROM (VALUES " + otherRows + ") AS t(month_start, kind, description, amount, context)",
                billMetrics);
    }

    private static String contextJson(OtherLine line) {
        if (line.allocation() != OtherLine.Allocation.CONTEXT || line.context() == null) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(line.context());
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
