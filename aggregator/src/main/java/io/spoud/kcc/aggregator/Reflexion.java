package io.spoud.kcc.aggregator;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.apache.kafka.common.serialization.Serdes;

@RegisterForReflection(classNames = {
        "org.apache.kafka.common.serialization.Serdes.String",
        "org.apache.kafka.common.serialization.Serdes$StringSerde",
        "org.apache.kafka.streams.processor.internals.DefaultKafkaClientSupplier"
})
public class Reflexion {}
