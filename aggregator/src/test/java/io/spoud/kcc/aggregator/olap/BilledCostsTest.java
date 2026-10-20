package io.spoud.kcc.aggregator.olap;

import io.spoud.kcc.aggregator.bills.BillEntity;
import io.spoud.kcc.aggregator.bills.BillLineEntity;
import io.spoud.kcc.aggregator.bills.BillLineEntity.Allocation;
import io.spoud.kcc.aggregator.bills.BillRate;
import io.spoud.kcc.aggregator.graphql.data.BilledCostRequest;
import io.spoud.kcc.aggregator.graphql.data.BilledCostResponse;
import io.spoud.kcc.aggregator.repository.MetricNameRepository;
import io.spoud.kcc.aggregator.stream.MetricReducer;
import io.spoud.kcc.aggregator.stream.TestConfigProperties;
import io.spoud.kcc.data.AggregatedDataWindowed;
import io.spoud.kcc.data.Bill;
import io.spoud.kcc.data.BillOtherLine;
import io.spoud.kcc.data.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private final List<BillEntity> bills = new ArrayList<>();
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
        view = new CostsView(olapInfra, null, null, costConfig);
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
        bills.add(new BillEntity("2026-09", null, null, List.of(
                BillLineEntity.byMetrics("Network write", 10.0, WRITE),
                BillLineEntity.byMetrics("Network read", 6.0, READ),
                BillLineEntity.other("Connect", 4.0, Allocation.CONTEXT, Map.of("team", "etl")),
                BillLineEntity.other("Support", 8.0, Allocation.USAGE, null),
                BillLineEntity.other("Credit", -2.0, Allocation.SHARED, null)),
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
        bills.add(new BillEntity("2026-09", null, null,
                List.of(BillLineEntity.other("Support", 3.0, Allocation.USAGE, null)), NOW, "test"));

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

    @Test
    @DisplayName("The partition line follows each topic's partition-hours, not the cluster's count")
    void partitionsFollowPartitionHours() {
        for (String hour : List.of("2026-09-03T10:00:00Z", "2026-09-03T11:00:00Z")) {
            usage(hour, "big", "kafka_topic_partition_count", 6, null);
            usage(hour, "small", "kafka_topic_partition_count", 2, null);
        }
        // Confluent's cluster-wide count has no topic to split by; it must not take part
        usage("2026-09-03T10:00:00Z", "cluster", "confluent_kafka_server_partition_count", 13, null);
        bills.add(new BillEntity("2026-09", null, null,
                List.of(BillLineEntity.byMetrics("Partitions", 8.0, "kafka_topic_partition_count")), NOW, "test"));

        var costs = costs("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        assertThat(prices(costs, "kafka_topic_partition_count")).containsOnly(Map.entry("big", 600.0), Map.entry("small", 200.0));
        assertThat(prices(costs, "confluent_kafka_server_partition_count")).isEmpty();
    }

    @Test
    @DisplayName("A bill's rate is its amount per unit of the usage in the hours it covers, and estimates the hours after it")
    void billRateEstimatesTheHoursAfterTheBill() {
        usage("2026-10-02T10:00:00Z", "a", WRITE, 30, null);
        usage("2026-10-05T10:00:00Z", "b", WRITE, 10, null);
        usage("2026-10-20T10:00:00Z", "b", WRITE, 1000, null); // after the bill's end
        bill("2026-10", "2026-10-10T00:00:00Z", 8.0, null);

        var bill = bills.getFirst();
        assertThat(repo.usageInBill(bill)).containsOnly(Map.entry(WRITE, 40.0));
        var rate = BillRate.of(WRITE, "Network write", 8.0, 40.0);
        assertThat(rate.costFactor()).isEqualTo(0.2);
        assertThat(BillRate.of(READ, "Network read", 3.0, 0).costFactor()).isNull();

        // a new price from the bill's end, at its rate
        rates.add(new CostsView.RatePeriod(WRITE, Instant.parse("2026-10-10T00:00:00Z"), null, 0, rate.costFactor()));
        var costs = costs("2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z");

        // a: 30 of the billed 40 -> $6; b: $2 billed, then 1000 at $0.2 estimated
        assertThat(prices(costs, WRITE)).containsEntry("a", 600.0).hasEntrySatisfying("b", c -> assertThat(c).isCloseTo(20200.0, within(1e-6)));
        assertThat(estimated(costs, WRITE)).containsEntry("a", 0.0).hasEntrySatisfying("b", c -> assertThat(c).isCloseTo(20000.0, within(1e-6)));
    }

    @Test
    @DisplayName("A bill saved before lines gives the costs it gave then")
    void aBillSavedBeforeLinesKeepsItsCosts() {
        usage("2026-09-03T10:00:00Z", "a", WRITE, 30, null);
        usage("2026-09-03T10:00:00Z", "b", WRITE, 10, null);
        usage("2026-09-03T10:00:00Z", "a", "kafka_topic_partition_count", 4, null);
        rate("kafka_topic_partition_count", 0.5);
        bills.add(BillEntity.fromAvro(Bill.newBuilder()
                .setMonth("2026-09").setNetworkWrite(10.0).setNetworkRead(6.0)
                .setOtherLines(List.of(
                        BillOtherLine.newBuilder().setDescription("Connect").setAmount(4.0).setAllocation("CONTEXT")
                                .setContext(Map.of("team", "etl")).build(),
                        BillOtherLine.newBuilder().setDescription("Support").setAmount(8.0).setAllocation("USAGE").build(),
                        BillOtherLine.newBuilder().setDescription("Credit").setAmount(-2.0).setAllocation("SHARED").build()))
                .setUpdatedAt(NOW).build()));

        var costs = costs("2026-09-01T00:00:00Z", "2026-09-16T00:00:00Z");

        // the same as otherLines(), entered as lines
        assertThat(prices(costs, WRITE)).containsOnly(Map.entry("a", 750.0), Map.entry("b", 250.0));
        assertThat(prices(costs, AggregatedMetricsRepository.OTHER)).containsOnly(
                Map.entry("etl", 200.0), Map.entry("a", 600.0), Map.entry("b", 200.0), Map.entry("<shared>", 200.0));
        // the partition line the old bill left empty is still estimated
        assertThat(estimated(costs, "kafka_topic_partition_count")).containsOnly(Map.entry("a", 200.0));
    }

    @Test
    @DisplayName("A line shared by several metrics goes by their usage together")
    void aLineWithSeveralMetrics() {
        usage("2026-09-03T10:00:00Z", "a", WRITE, 30, null);
        usage("2026-09-03T10:00:00Z", "b", READ, 10, null);
        bills.add(new BillEntity("2026-09", null, null,
                List.of(BillLineEntity.byMetrics("Network", 10.0, WRITE, READ)), NOW, "test"));

        var costs = costs("2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z");

        assertThat(prices(costs, WRITE)).containsOnly(Map.entry("a", 750.0));
        assertThat(prices(costs, READ)).containsOnly(Map.entry("b", 250.0));
    }

    @Test
    @DisplayName("A cluster's bill is shared only over its cluster; the others keep their pricing rules")
    void aClustersBillStaysInItsCluster() {
        usageIn("strimzi", "2026-10-03T10:00:00Z", "a", "kafka_log_log_size", 30);
        usageIn("strimzi", "2026-10-03T10:00:00Z", "b", "kafka_log_log_size", 10);
        usageIn("confluent", "2026-10-03T10:00:00Z", "c", "kafka_log_log_size", 50);
        usageIn("confluent", "2026-10-03T10:00:00Z", "c", WRITE, 10);
        rate("kafka_log_log_size", 0.01);
        rate(WRITE, 0.1);
        bills.add(new BillEntity("2026-10", "strimzi", null, List.of(
                BillLineEntity.byMetrics("Storage", 8.0, "kafka_log_log_size"),
                BillLineEntity.other("Brokers", 4.0, Allocation.USAGE, null),
                BillLineEntity.other("Operations", 3.1, Allocation.SHARED, null)), NOW, "test"));

        var costs = costs("2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z", List.of("cluster", "team"));

        assertThat(prices(costs, "kafka_log_log_size")).containsOnly(
                Map.entry("strimzi/a", 600.0), Map.entry("strimzi/b", 200.0), Map.entry("confluent/c", 50.0));
        assertThat(estimated(costs, "kafka_log_log_size")).containsEntry("strimzi/a", 0.0).containsEntry("confluent/c", 50.0);
        // the other cluster's metrics the bill doesn't name are estimated, not dropped
        assertThat(estimated(costs, WRITE)).containsOnly(Map.entry("confluent/c", 100.0));
        // overhead by the bill's own usage costs; the shared line shows under its cluster
        assertThat(prices(costs, AggregatedMetricsRepository.OTHER)).containsOnly(
                Map.entry("strimzi/a", 300.0), Map.entry("strimzi/b", 100.0), Map.entry("strimzi/<shared>", 310.0));
        assertThat(costs.months()).singleElement().satisfies(m -> {
            assertThat(m.billed()).isTrue();
            assertThat(m.clusters()).containsExactly("strimzi");
        });
    }

    @Test
    @DisplayName("Each cluster's bill spreads its overhead over its own cluster")
    void overheadStaysWithItsBill() {
        usageIn("one", "2026-10-03T10:00:00Z", "a", WRITE, 10);
        usageIn("two", "2026-10-03T10:00:00Z", "b", WRITE, 10);
        bills.add(new BillEntity("2026-10", "one", null, List.of(BillLineEntity.byMetrics("Network", 1.0, WRITE),
                BillLineEntity.other("Support", 5.0, Allocation.USAGE, null)), NOW, "test"));
        bills.add(new BillEntity("2026-10", "two", null, List.of(BillLineEntity.byMetrics("Network", 3.0, WRITE),
                BillLineEntity.other("Support", 7.0, Allocation.USAGE, null)), NOW, "test"));

        var costs = costs("2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z", List.of("cluster"));

        assertThat(prices(costs, WRITE)).containsOnly(Map.entry("one", 100.0), Map.entry("two", 300.0));
        assertThat(prices(costs, AggregatedMetricsRepository.OTHER)).containsOnly(Map.entry("one", 500.0), Map.entry("two", 700.0));
        assertThat(costs.months()).singleElement().satisfies(m -> assertThat(m.clusters()).containsExactly("one", "two"));
    }

    @Test
    @DisplayName("A month with a bill for all clusters ignores a cluster's bill the topic still holds")
    void aBillForAllClustersWins() {
        usageIn("one", "2026-10-03T10:00:00Z", "a", WRITE, 10);
        bills.add(new BillEntity("2026-10", null, null, List.of(BillLineEntity.byMetrics("Network", 2.0, WRITE)), NOW, "test"));
        bills.add(new BillEntity("2026-10", "one", null, List.of(BillLineEntity.byMetrics("Network", 9.0, WRITE)), NOW, "test"));

        var costs = costs("2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z", List.of("cluster"));

        assertThat(prices(costs, WRITE)).containsOnly(Map.entry("one", 200.0));
    }

    @Test
    @DisplayName("A cluster's bill counts the usage of its own cluster for its rates")
    void aClustersBillRatesUseItsClusterOnly() {
        usageIn("one", "2026-10-03T10:00:00Z", "a", WRITE, 10);
        usageIn("two", "2026-10-03T10:00:00Z", "b", WRITE, 90);
        var bill = new BillEntity("2026-10", "one", null, List.of(BillLineEntity.byMetrics("Network", 1.0, WRITE)), NOW, "test");

        assertThat(repo.usageInBill(bill)).containsOnly(Map.entry(WRITE, 10.0));
        assertThat(repo.clusters()).containsExactly("one", "two");
    }

    private void usage(String hour, String team, String metric, double value, Double cost) {
        usageIn(null, hour, team, metric, value);
    }

    private void usageIn(String cluster, String hour, String team, String metric, double value) {
        Instant start = Instant.parse(hour);
        var context = new HashMap<String, String>(Map.of("team", team));
        if (cluster != null) {
            context.put("cluster", cluster);
        }
        repo.insertRow(AggregatedDataWindowed.newBuilder()
                .setStartTime(start).setEndTime(start.plus(Duration.ofHours(1)))
                .setEntityType(EntityType.TOPIC).setName(team).setInitialMetricName(metric)
                .setValue(value).setTags(Map.of()).setContext(context)
                .build());
        repo.flushToDb();
    }

    /** A bill with Confluent's network lines; an empty amount is estimated, as before lines. */
    private void bill(String month, String coveredUntil, Double write, Double read) {
        bills.add(new BillEntity(month, null, coveredUntil == null ? null : Instant.parse(coveredUntil), List.of(
                BillLineEntity.byMetrics("Network write", write, WRITE),
                BillLineEntity.byMetrics("Network read", read, READ)), NOW, "test"));
    }

    private BilledCostResponse costs(String from, String to) {
        return costs(from, to, List.of("team"));
    }

    private BilledCostResponse costs(String from, String to, List<String> keys) {
        view.define(rates, bills);
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
                result.put(String.join("/", share.contextValues()),
                        Math.round((estimated ? share.estimatedPrice() : share.price()) * 1e6) / 1e6)));
        return result;
    }
}
