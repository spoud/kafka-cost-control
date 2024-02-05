package io.spoud.kcc.aggregator.stream.config;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public class KafkaResource implements QuarkusTestResourceLifecycleManager {

  public static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.1")).withNetwork(NetworkConfig.NETWORK);

  @Override
  public Map<String, String> start() {
    kafka.start();
    return Map.of("kafka.bootstrap.servers", kafka.getBootstrapServers());
  }

  @Override
  public void stop() {
    kafka.stop();
  }

  @Override
  public int order() {
    return 1;
  }
}
