package io.spoud.kcc.aggregator.stream.utils;

import io.spoud.kcc.aggregator.stream.serialization.JSONSerde;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class KafkaTestUtils {

  public static <K extends String, V extends SpecificRecord>
      KafkaProducer<K, V> createAvroProducer() {
    final Properties props = getBaseProperties();
    props.put(
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringSerializer");
    props.put(
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
        "io.confluent.kafka.serializers.KafkaAvroSerializer");
    return new KafkaProducer<>(props);
  }

  public static <K extends String, V> KafkaProducer<K, V> createJsonProducer() {
    final Properties props = getBaseProperties();
    props.put(
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getCanonicalName());
    props.put(
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            JSONSerde.class.getCanonicalName());
    return new KafkaProducer<>(props);
  }

  public static AdminClient createAdminClient() {
    return KafkaAdminClient.create(getBaseMap());
  }

    public static final String PREFIX = "kafka.";

    public static Properties getBaseProperties() {
        Properties properties = new Properties();
        Config config = ConfigProvider.getConfig();
        config
                .getPropertyNames()
                .forEach(
                        key -> {
                            if (key.startsWith(PREFIX)) {
                                String value = config.getConfigValue(key).getValue();
                                if (value != null) {
                                    properties.setProperty(key.substring(PREFIX.length()), value);
                                }
                            }
                        });
        return properties;
    }

    public static Map<String, Object> getBaseMap() {
        Map<String, Object> map = new HashMap<>();
        getBaseProperties().forEach((key,value) -> map.put(String.valueOf(key), value));
        return map;
    }

}
