package io.spoud.kcc.aggregator;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(classNames = {
        "org.apache.kafka.common.serialization.Serdes.String",
        "org.apache.kafka.common.serialization.Serdes$StringSerde",
        "org.apache.kafka.streams.processor.internals.DefaultKafkaClientSupplier"
})
public class Reflexion {}
