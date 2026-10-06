package io.spoud.kcc.aggregator.bills;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Identifier;
import io.spoud.kcc.aggregator.CostControlConfigProperties;
import io.spoud.kcc.aggregator.stream.serialization.SerdeFactory;
import io.spoud.kcc.data.Bill;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.ServiceUnavailableException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The bills, kept in a compacted topic keyed by month, like the rules. Read outside Kafka Streams
 * on purpose: the topic is new, and a missing topic must disable bills rather than stop the whole
 * application. The topic is created when missing; where the application's user may not create
 * topics, it has to be created beforehand (compacted, one partition).
 */
@ApplicationScoped
public class BillsRepository {

    private static final Duration LOAD_WAIT = Duration.ofSeconds(5);

    private final Map<String, Object> kafkaConfig;
    private final String topic;
    private final SerdeFactory serdes;
    private final Map<String, BillEntity> bills = new ConcurrentHashMap<>();
    private final CountDownLatch loaded = new CountDownLatch(1);
    private volatile boolean running = true;
    private volatile String unavailable;
    private KafkaProducer<String, Bill> producer;
    private Thread reader;

    public BillsRepository(@Identifier("default-kafka-broker") Map<String, Object> kafkaConfig,
                           CostControlConfigProperties config, SerdeFactory serdes) {
        this.kafkaConfig = kafkaConfig;
        this.topic = config.topicBills();
        this.serdes = serdes;
    }

    void start(@Observes StartupEvent event) {
        reader = new Thread(this::readTopic, "bills-reader");
        reader.setDaemon(true);
        reader.start();
    }

    void stop(@Observes ShutdownEvent event) {
        running = false;
        if (reader != null) {
            reader.interrupt();
        }
        synchronized (this) {
            if (producer != null) {
                producer.close(Duration.ofSeconds(5));
            }
        }
    }

    /** All bills, newest month first. */
    public List<BillEntity> all() {
        awaitLoaded();
        return bills.values().stream().sorted(Comparator.comparing(BillEntity::month).reversed()).toList();
    }

    public Map<YearMonth, BillEntity> byMonth() {
        awaitLoaded();
        Map<YearMonth, BillEntity> byMonth = new ConcurrentHashMap<>();
        bills.values().forEach(bill -> byMonth.put(YearMonth.parse(bill.month()), bill));
        return byMonth;
    }

    public BillEntity save(BillEntity bill) {
        send(bill.month(), bill.toAvro());
        bills.put(bill.month(), bill);
        return bill;
    }

    public Optional<BillEntity> delete(String month) {
        BillEntity existing = bills.get(month);
        send(month, null);
        bills.remove(month);
        return Optional.ofNullable(existing);
    }

    private void send(String key, Bill value) {
        awaitLoaded();
        try {
            producer().send(new ProducerRecord<>(topic, key, value)).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted while saving the bill.");
        } catch (ExecutionException | TimeoutException e) {
            Log.errorv(e, "Could not write the bill for {0} to topic {1}", key, topic);
            throw new ServiceUnavailableException("The bill could not be saved: " + rootMessage(e));
        }
    }

    private void awaitLoaded() {
        try {
            if (!loaded.await(LOAD_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ServiceUnavailableException(unavailable != null ? unavailable
                        : "The bills are still loading, try again in a moment.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted while loading the bills.");
        }
    }

    private synchronized KafkaProducer<String, Bill> producer() {
        if (producer == null) {
            var props = new Properties();
            props.putAll(kafkaConfig);
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            producer = new KafkaProducer<>(props, new StringSerializer(), serdes.getBillSerde().serializer());
        }
        return producer;
    }

    /** Creates the topic if needed, reads it from the beginning, then follows it. Retries until it works. */
    private void readTopic() {
        long backoff = 5_000;
        while (running) {
            try {
                ensureTopic();
                follow();
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                String reason = "Bills are unavailable: topic " + topic + " can't be read (" + rootMessage(e) + ").";
                if (!reason.equals(unavailable)) {
                    Log.warnv("{0} Retrying.", reason);
                }
                unavailable = reason;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, 300_000);
            }
        }
    }

    private void ensureTopic() throws InterruptedException, ExecutionException, TimeoutException {
        try (AdminClient admin = AdminClient.create(kafkaConfig)) {
            Set<String> names = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            if (names.contains(topic)) {
                return;
            }
            var newTopic = new NewTopic(topic, Optional.of(1), Optional.empty())
                    .configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT));
            try {
                admin.createTopics(List.of(newTopic)).all().get(30, TimeUnit.SECONDS);
                Log.infov("Created the bills topic {0}", topic);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    throw e;
                }
            }
        }
    }

    private void follow() {
        var props = new Properties();
        props.putAll(kafkaConfig);
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), serdes.getBillSerde().deserializer())) {
            var partitions = consumer.partitionsFor(topic, Duration.ofSeconds(30)).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            while (running) {
                for (var record : consumer.poll(Duration.ofSeconds(1))) {
                    if (record.value() == null) {
                        bills.remove(record.key());
                    } else {
                        bills.put(record.key(), BillEntity.fromAvro(record.value()));
                    }
                }
                if (loaded.getCount() > 0 && partitions.stream().allMatch(p -> consumer.position(p) >= ends.get(p))) {
                    unavailable = null;
                    loaded.countDown();
                    Log.infov("Loaded {0} bill(s) from {1}", bills.size(), topic);
                }
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
