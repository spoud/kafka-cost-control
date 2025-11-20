import datetime
import os
import sys

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import pytest
from kafka import KafkaConsumer, TopicPartition
from testcontainers.kafka import RedpandaContainer

from confluent_scraping_agent.config import AppConfig
from confluent_scraping_agent.metric import Metric
from confluent_scraping_agent.metric_output import (
    MetricSendException,
    close_producer,
    send_metrics,
)


def test_produce_metrics():
    with RedpandaContainer() as redpanda:
        connection = redpanda.get_bootstrap_server()
        target_topic = "test-metrics"
        config = AppConfig.model_construct(
            kafka_settings={"bootstrap_servers": connection}, metrics_topic=target_topic
        )

        ts = datetime.datetime.now()
        m1 = Metric.create_topic_metric(
            "test-topic", "bytes_in", 100, ts, {"env": "test"}
        )
        m2 = Metric.create_principal_metric(
            "test-principal", "bytes_in", 100, ts, {"env": "test"}
        )

        send_metrics(metrics=[m1, m2], get_app_config=lambda: config)

        # Consume from the topic
        consumer = KafkaConsumer(
            target_topic,
            bootstrap_servers=connection,
            group_id="test-group",
            value_deserializer=lambda x: Metric.model_validate_json(x),
            auto_offset_reset="earliest",
        )
        batches = consumer.poll(timeout_ms=3000)
        records = batches.get(TopicPartition(topic=target_topic, partition=0), [])

        assert (
            len(records) == 2
        ), f"Expected 2 records, got {len(records) if records else 0}"
        received_metrics = [record.value for record in records]
        assert m1 in received_metrics
        assert m2 in received_metrics
        consumer.close()
        close_producer()


def test_connection_failure():
    config = AppConfig.model_construct(
        kafka_settings={"bootstrap_servers": "invalid:9092"},
        metrics_topic="test-metrics",
    )
    ts = datetime.datetime.now()
    m1 = Metric.create_topic_metric("test-topic", "bytes_in", 100, ts, {"env": "test"})
    with pytest.raises(MetricSendException) as excinfo:
        send_metrics(metrics=[m1], get_app_config=lambda: config)
    assert "Failed to connect to Kafka" in str(excinfo.value)
    close_producer()
