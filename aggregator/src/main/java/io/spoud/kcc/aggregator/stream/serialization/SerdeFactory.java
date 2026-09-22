package io.spoud.kcc.aggregator.stream.serialization;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import io.smallrye.common.annotation.Identifier;
import io.spoud.kcc.aggregator.data.RawTelegrafData;
import io.spoud.kcc.data.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.util.ClassSecurityValidator;
import org.apache.kafka.common.serialization.Serde;

import java.util.Map;

@ApplicationScoped
public class SerdeFactory {

    static {
        // Avro 1.12.2 added ClassSecurityValidator, which by default refuses to resolve any
        // class outside a small trusted set when (de)serializing specific records - our own
        // generated classes get rejected with a SecurityException otherwise. Trust our own
        // package on top of the default rules rather than the whole classpath.
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(
                ClassSecurityValidator.DEFAULT,
                clazz -> ContextData.class.getPackageName().equals(clazz.getPackageName())));
    }

    private final Map<String, Object> kafkaConfig;

    @Inject
    public SerdeFactory(@Identifier("default-kafka-broker") Map<String, Object> kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }

    public <T extends SpecificRecord> Serde<T> getAvroSerde(boolean isKey, Class<T> clazz) {
        SpecificAvroSerde<T> avroSerDes = new SpecificAvroSerde<>();
        avroSerDes.configure(kafkaConfig, isKey);
        return avroSerDes;
    }

    public Serde<AggregatedDataKey> getAggregatedKeySerde() {
        return getAvroSerde(true, AggregatedDataKey.class);
    }

    public Serde<AggregatedData> getAggregatedSerde() {
        return getAvroSerde(false, AggregatedData.class);
    }

    public Serde<AggregatedDataTableFriendly> getAggregatedTableFriendlySerde() {
        return getAvroSerde(false, AggregatedDataTableFriendly.class);
    }

    public Serde<AggregatedDataWindowed> getAggregatedWindowedSerde() {
        return getAvroSerde(false, AggregatedDataWindowed.class);
    }

    public Serde<PricingRule> getPricingRuleSerde() {
        return getAvroSerde(false, PricingRule.class);
    }

    public Serde<ContextData> getContextDataSerde() {
        return getAvroSerde(false, ContextData.class);
    }

    public Serde<RawTelegrafData> getRawTelegrafSerde() {
        return new JSONSerde<>(RawTelegrafData.class);
    }

}
