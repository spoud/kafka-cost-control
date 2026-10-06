package io.spoud.kcc.aggregator.bills;

import java.util.function.Function;

/**
 * The bill's lines that are split by usage, and the metric each one follows. Request bytes flow
 * client->broker (produce), which Confluent bills as network write; response bytes flow
 * broker->client (fetch), billed as network read. Partitions follow the kafka-scraper's per-topic
 * count (hourly max, so each topic's share is its partition-hours): Confluent's own partition_count
 * is per cluster and can't be split by topic.
 */
public enum BillLine {
    NETWORK_WRITE("confluent_kafka_server_request_bytes", BillEntity::networkWrite),
    NETWORK_READ("confluent_kafka_server_response_bytes", BillEntity::networkRead),
    STORAGE("confluent_kafka_server_retained_bytes", BillEntity::storage),
    PARTITIONS("kafka_topic_partition_count", BillEntity::partitions);

    private final String metric;
    private final Function<BillEntity, Double> amount;

    BillLine(String metric, Function<BillEntity, Double> amount) {
        this.metric = metric;
        this.amount = amount;
    }

    public String metric() {
        return metric;
    }

    /** The billed amount in dollars, null when the bill doesn't have this line. */
    public Double amount(BillEntity bill) {
        return amount.apply(bill);
    }
}
