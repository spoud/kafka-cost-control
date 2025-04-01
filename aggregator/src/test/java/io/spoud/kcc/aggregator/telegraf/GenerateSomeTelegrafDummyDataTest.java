package io.spoud.kcc.aggregator.telegraf;

import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.aggregator.stream.serialization.SerdeFactory;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Disabled("for generating some telegraf dummy data against running instance, not a real test")
public class GenerateSomeTelegrafDummyDataTest {

    public static final String METRICS_RAW_TELEGRAF_TOPIC = "metrics-raw-telegraf-env";

    KafkaProducer<String, RawTelegrafData> producer() {
        Map<String, Object> config = Map.of(
                "client.id", "kcc-dummy-telegraf-data-generator",
                "bootstrap.servers", "localhost:9092",
                "schema.registry.url", "http://localhost:8081",
                "key.serializer", StringSerializer.class,
                "value.serializer", new SerdeFactory(null).getRawTelegrafSerde().getClass()
        );
        return new KafkaProducer<>(config);
    }

    @Test
    void produceSomeTelegrafData() {
        List<String> metrics = List.of("confluent_kafka_server_request_bytes",
                "confluent_kafka_server_received_bytes");
        AtomicInteger counter = new AtomicInteger();
        try (KafkaProducer<String, RawTelegrafData> producer = producer()) {
            for (String metric : metrics) {
                for (int i = 0; i < 10; i++) {
                    RawTelegrafData rawTelegrafData = new RawTelegrafData(Instant.now(),
                            metric,
                            Map.of("gauge", 1024 * (int) (Math.random() * 1024)),
                            Map.of("env", "dev",
                                    "principal_id", "sa-abcd",
                                    "type", "Produce"));
                    ProducerRecord<String, RawTelegrafData> record = new ProducerRecord<>(
                            METRICS_RAW_TELEGRAF_TOPIC,
                            rawTelegrafData
                    );
                    producer.send(record);
                    counter.incrementAndGet();
                }
            }

            RawTelegrafData rawTelegrafData = new RawTelegrafData(Instant.now(),
                    "confluent_kafka_server_retained_bytes",
                    Map.of("gauge", 1024*1024 * (int) (Math.random() * 1024)),
                    Map.of("env", "dev",
                            "principal_id", "sa-abcd",
                            "type", "idk?"));
            ProducerRecord<String, RawTelegrafData> record = new ProducerRecord<>(
                    METRICS_RAW_TELEGRAF_TOPIC,
                    rawTelegrafData
            );
            producer.send(record);
            counter.incrementAndGet();
        }
        Log.infof("Produced {} data points to {}", counter.get(), METRICS_RAW_TELEGRAF_TOPIC);
        Log.info("Produced " + counter.get() + " telegraf data points");
    }

}

