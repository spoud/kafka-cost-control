package io.spoud.kcc.aggregator.olap;

import io.spoud.kcc.aggregator.bills.BillEntity;
import io.spoud.kcc.aggregator.bills.OtherLine;
import io.spoud.kcc.aggregator.graphql.data.BilledCostRequest;
import io.spoud.kcc.aggregator.graphql.data.BilledCostResponse;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.stream.MetricReducer;
import io.spoud.kcc.aggregator.stream.TestConfigProperties;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.data.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BilledCostsTest {

    private static final String WRITE = "confluent_kafka_server_request_bytes";
    private static final String READ = "confluent_kafka_server_response_bytes";
    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    private AggregatedMetricsRepository repo;
    private CostsView view;
    private final Map<YearMonth, BillEntity> bills = new HashMap<>();
    /** The pricing rules' rates; estimates come from them, computed when asked. */
    private final List<CostsView.RatePeriod> rates = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        var olapConfig = FakeOlapConfig.builder().build();
        var olapInfra = new OlapInfra(olapConfig);
        olapInfra.init();
        var costConfig = TestConfigProperties.builder().build();
        repo = new AggregatedMetricsRepository(olapConfig, costConfig, olapInfra,
                new MetricNameRepository(new MetricReducer(costConfig), olapInfra));
        view = new CostsView(olapInfra, null, null);
    }

    private void rate(String metric, double costFactor) {
        rates.add(new CostsView.RatePeriod(metric, null, null, 0, costFactor));
    }

    @Test
    @DisplayName("A month's line goes to each team in proportion to its usage")
    void splitsAMonthByUsage() {
        usage("2026-09-03T10:00:00Z", "a", WRITE, 30, null);
        usage("2026-09-20T10:00:00Z", "b", WRITE, 10, null);
        bill("2026-09", null, 10.0, null);

        var costs = costs("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        assertThat(prices(costs, WRITE)).containsEntry("a", 750.0).containsEntry("b", 250.0);
        assertThat(estimated(costs, WRITE)).containsEntry("a", 0.0).containsEntry("b", 0.0);
        assertThat(costs.months()).singleElement().satisfies(m -> {
            assertThat(m.month()).isEqualTo("2026-09");
            assertThat(m.billed()).isTrue();
        });
    }

    @Test
    @DisplayName("A range covering part of a month gets the usage-weighted part of the month's line")
    void partOfAMonthIsWeightedByUsage() {
        usage("2026-09-05T10:00:00Z", "a", WRITE, 10, null);
        usage("2026-09-20T10:00:00Z", "b", WRITE, 30, null);
        bill("2026-09", null, 10.0, null);

        var costs = costs("2026-09-15T00:00:00Z", "2026-10-01T00:00:00Z");

        // b's 30 of the month's 40: three quarters of the line, not half (the share of days)
        assertThat(prices(costs, WRITE)).containsOnly(Map.entry("b", 750.0));
    }

    @Test
    @DisplayName("A range across months adds up each month's part of its own bill")
    void acrossMonthsEachMonthUsesItsBill() {
        usage("2026-09-25T10:00:00Z", "a", WRITE, 10, null);
        usage("2026-10-02T10:00:00Z", "a", WRITE, 10, null);
        usage("2026-10-03T10:00:00Z", "b", WRITE, 10, null);
        usage("2026-10-20T10:00:00Z", "b", WRITE, 20, null);
        bill("2026-09", null, 10.0, null);
        bill("2026-10", null, 40.0, null);

        var costs = costs("2026-09-20T00:00:00Z", "2026-10-10T00:00:00Z");

        // September: a's 10 are all of September's usage -> $10. October: of 40 in the month,
        // a's 10 and b's first 10 fall in the range -> $10 each; b's 20 on the 20th are outside.
        assertThat(prices(costs, WRITE)).containsEntry("a", 2000.0).containsEntry("b", 1000.0);
        assertThat(costs.months()).extracting(BilledCostResponse.MonthBilling::month)
                .containsExactly("2026-09", "2026-10");
    }

    @Test
    @DisplayName("A month without a bill uses the rate card and says it is estimated")
    void withoutABillTheRateCardIsAnEstimate() {
        usage("2026-11-02T10:00:00Z", "a", WRITE, 10, null);
        usage("2026-11-02T10:00:00Z", "b", READ, 10, null);
        rate(WRITE, 0.025);
        rate(READ, 0.05);

        var costs = costs("2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z");

        assertThat(prices(costs, WRITE)).containsOnly(Map.entry("a", 25.0));
        assertThat(estimated(costs, WRITE)).containsOnly(Map.entry("a", 25.0));
        assertThat(prices(costs, READ)).containsOnly(Map.entry("b", 50.0));
        assertThat(costs.months()).singleElement().satisfies(m -> assertThat(m.billed()).isFalse());
    }

    @Test
    @DisplayName("After a month-to-date bill's end, the rate card takes over")
    void monthToDateBillThenEstimate() {
        usage("2026-10-05T10:00:00Z", "a", WRITE, 10, null);
        usage("2026-10-15T10:00:00Z", "b", WRITE, 10, null);
        rate(WRITE, 0.07);
        bill("2026-10", "2026-10-10T00:00:00Z", 10.0, null);

        var costs = costs("2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z");

        assertThat(prices(costs, WRITE)).containsEntry("a", 1000.0).containsEntry("b", 70.0);
        assertThat(estimated(costs, WRITE)).containsEntry("a", 0.0).containsEntry("b", 70.0);
        assertThat(costs.months()).singleElement()
                .satisfies(m -> assertThat(m.billedUntil()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z")));
    }

    @Test
    @DisplayName("A line the bill doesn't have is estimated; billed months don't add other priced metrics")
    void missingLineIsEstimated() {
        usage("2026-09-03T10:00:00Z", "a", WRITE, 10, null);
        usage("2026-09-03T10:00:00Z", "a", READ, 10, null);
        usage("2026-09-03T10:00:00Z", "a", "some_other_priced_metric", 10, null);
        rate(WRITE, 0.01);
        rate(READ, 0.03);
        rate("some_other_priced_metric", 0.9);
        bill("2026-09", null, 5.0, null);

        var costs = costs("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        assertThat(prices(costs, WRITE)).containsOnly(Map.entry("a", 500.0));
        assertThat(estimated(costs, READ)).containsOnly(Map.entry("a", 30.0));
        assertThat(costs.metrics()).extracting(BilledCostResponse.MetricCosts::metric)
                .doesNotContain("some_other_priced_metric");
    }

    @Test
    @DisplayName("Other lines go to their context, are spread by usage costs, or stay shared; an unmeasured line is shared")
    void otherLines() {
        usage("2026-09-03T10:00:00Z", "a", WRITE, 30, null);
        usage("2026-09-03T10:00:00Z", "b", WRITE, 10, null);
        bills.put(YearMonth.of(2026, 9), new BillEntity("2026-09", null, 10.0, 6.0, null, null, List.of(
                new OtherLine("Connect", 4.0, OtherLine.Allocation.CONTEXT, Map.of("team", "etl")),
                new OtherLine("Support", 8.0, OtherLine.Allocation.USAGE, null),
                new OtherLine("Credit", -2.0, OtherLine.Allocation.SHARED, null)),
                NOW, "test"));

        // the first 15 of September's 30 days
        var costs = costs("2026-09-01T00:00:00Z", "2026-09-16T00:00:00Z");

        assertThat(prices(costs, WRITE)).containsOnly(Map.entry("a", 750.0), Map.entry("b", 250.0));
        // by time: Connect half -> etl 200; credit half -100 and the unmeasured read line half 300
        // -> shared 200. Support follows the billed usage, all of which is on 3 September: all 800,
        // by write costs 3:1 -> a 600, b 200
        assertThat(prices(costs, AggregatedMetricsRepository.OTHER)).containsOnly(
                Map.entry("etl", 200.0), Map.entry("a", 600.0), Map.entry("b", 200.0), Map.entry("<shared>", 200.0));
    }

    @Test
    @DisplayName("A line spread by usage is shared when nothing usage-based was billed")
    void usageSpreadWithoutUsageIsShared() {
        bills.put(YearMonth.of(2026, 9), new BillEntity("2026-09", null, null, null, null, null,
                List.of(new OtherLine("Support", 3.0, OtherLine.Allocation.USAGE, null)), NOW, "test"));

        var costs = costs("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        assertThat(prices(costs, AggregatedMetricsRepository.OTHER)).containsOnly(Map.entry("<shared>", 300.0));
    }

    @Test
    @DisplayName("Estimates use the rules as they are now: a correction applies to the past at once")
    void estimatesFollowTheRulesWhenAsked() {
        usage("2026-11-02T10:00:00Z", "a", WRITE, 10, null);
        rate(WRITE, 0.01);
        assertThat(prices(costs("2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z"), WRITE)).containsOnly(Map.entry("a", 10.0));

        rates.clear();
        rate(WRITE, 0.02);

        // no reprocess: the next query prices the same hour with the corrected rule
        assertThat(prices(costs("2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z"), WRITE)).containsOnly(Map.entry("a", 20.0));
    }

    @Test
    @DisplayName("A dated price change keeps the earlier price for the hours before it")
    void datedPriceChange() {
        Instant change = Instant.parse("2026-11-15T00:00:00Z");
        usage("2026-11-02T10:00:00Z", "a", WRITE, 10, null);
        usage("2026-11-20T10:00:00Z", "b", WRITE, 10, null);
        rates.add(new CostsView.RatePeriod(WRITE, null, change, 0, 0.01));
        rates.add(new CostsView.RatePeriod(WRITE, change, null, 0, 0.05));

        assertThat(prices(costs("2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z"), WRITE))
                .containsOnly(Map.entry("a", 10.0), Map.entry("b", 50.0));
    }

    @Test
    @DisplayName("Without grouping, each metric has one total")
    void withoutGroupingOneTotalPerMetric() {
        usage("2026-09-03T10:00:00Z", "a", WRITE, 30, null);
        usage("2026-09-20T10:00:00Z", "b", WRITE, 10, null);
        bill("2026-09", null, 10.0, null);

        var costs = costs("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z", List.of());

        assertThat(costs.metrics()).singleElement().satisfies(m -> assertThat(m.shares()).singleElement()
                .satisfies(share -> {
                    assertThat(share.price()).isCloseTo(1000.0, within(1e-9));
                    assertThat(share.contextValues()).isEmpty();
                }));
    }

    private void usage(String hour, String team, String metric, double value, Double cost) {
        Instant start = Instant.parse(hour);
        repo.insertRow(AggregatedDataWindowed.newBuilder()
                .setStartTime(start).setEndTime(start.plus(Duration.ofHours(1)))
                .setEntityType(EntityType.TOPIC).setName(team).setInitialMetricName(metric)
                .setValue(value).setTags(Map.of()).setContext(Map.of("team", team))
                .build());
        repo.flushToDb();
    }

    private void bill(String month, String coveredUntil, Double write, Double read) {
        bills.put(YearMonth.parse(month), new BillEntity(month,
                coveredUntil == null ? null : Instant.parse(coveredUntil), write, read, null, null, List.of(), NOW, "test"));
    }

    private BilledCostResponse costs(String from, String to) {
        return costs(from, to, List.of("team"));
    }

    private BilledCostResponse costs(String from, String to, List<String> keys) {
        view.define(rates, bills.values());
        return repo.calculateBilledCosts(new BilledCostRequest(Instant.parse(from), Instant.parse(to), keys), bills, NOW);
    }

    private static Map<String, Double> prices(BilledCostResponse costs, String metric) {
        return byTeam(costs, metric, false);
    }

    private static Map<String, Double> estimated(BilledCostResponse costs, String metric) {
        return byTeam(costs, metric, true);
    }

    private static Map<String, Double> byTeam(BilledCostResponse costs, String metric, boolean estimated) {
        var result = new HashMap<String, Double>();
        costs.metrics().stream().filter(m -> m.metric().equals(metric)).forEach(m -> m.shares().forEach(share ->
                result.put(share.contextValues().getFirst(),
                        Math.round((estimated ? share.estimatedPrice() : share.price()) * 1e6) / 1e6)));
        return result;
    }
}
