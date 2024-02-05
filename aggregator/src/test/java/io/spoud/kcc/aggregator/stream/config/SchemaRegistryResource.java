package io.spoud.kcc.aggregator.stream.config;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.spoud.kcc.aggregator.stream.container.SchemaRegistryContainer;
import java.util.Map;

public class SchemaRegistryResource implements QuarkusTestResourceLifecycleManager {

  public static SchemaRegistryContainer schemaRegistry =
      new SchemaRegistryContainer("7.4.1")
          .withKafka(KafkaResource.kafka)
          .withNetwork(NetworkConfig.NETWORK);

  @Override
  public Map<String, String> start() {
    schemaRegistry.start();
    return Map.of("kafka.schema.registry.url", schemaRegistry.getSchemaRegistryUrl());
  }

  @Override
  public void stop() {
    schemaRegistry.stop();
  }

  @Override
  public int order() {
    return 2;
  }
}
