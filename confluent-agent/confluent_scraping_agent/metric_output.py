import logging
from json import JSONDecodeError
from typing import Callable, Optional

from kafka import KafkaProducer
from kafka.errors import KafkaError, KafkaTimeoutError
from pydantic import ValidationError

from confluent_scraping_agent.config import AppConfig, get_config
from confluent_scraping_agent.metric import Metric

# Set up logging
logger = logging.getLogger(__name__)


# Custom exception hierarchy
class MetricSendException(Exception):
    """Base exception for Kafka producer errors."""

    pass


class KafkaConnectionException(MetricSendException):
    """Exception raised when there's an issue connecting to Kafka."""

    pass


class KafkaConfigurationException(MetricSendException):
    """Exception raised when there's an issue with Kafka configuration."""

    pass


class KafkaSendException(MetricSendException):
    """Exception raised when there's an issue sending messages to Kafka."""

    pass


class MetricSerializationException(MetricSendException):
    """Exception raised when there's an issue serializing metrics."""

    pass


def metric_serializer(metric: Metric) -> bytes:
    """
    Serialize a Metric object to JSON bytes.

    Args:
        metric: The Metric object to serialize.

    Returns:
        bytes: The serialized metric as UTF-8 encoded JSON.

    Raises:
        ValidationError: If there's an issue with the metric validation.
        JSONDecodeError: If there's an issue with JSON serialization.
    """
    try:
        return metric.model_dump_json().encode("utf-8")
    except ValidationError as e:
        logger.error("Failed to serialize metric: %s", str(e))
        raise MetricSerializationException(
            f"Failed to serialize metric: {str(e)}"
        ) from e
    except JSONDecodeError as e:
        logger.error("JSON serialization error: %s", str(e))
        raise MetricSerializationException(f"JSON serialization error: {str(e)}") from e


inst: Optional[KafkaProducer] = None


def get_kafka_producer(
    get_app_config: Callable[[], AppConfig] = get_config,
) -> KafkaProducer:
    """
    Get or create a Kafka producer instance.

    Args:
        get_app_config: Function to retrieve the application configuration. Mainly for testing purposes.

    Returns:
        KafkaProducer: The Kafka producer instance.

    Raises:
        KafkaConfigurationException: If there's an issue with the Kafka configuration.
        KafkaConnectionException: If there's an issue connecting to Kafka.
    """
    global inst
    if inst is None:
        try:
            config = get_app_config()
            if not config.kafka_settings:
                raise KafkaConfigurationException(
                    "Kafka settings are empty or not configured"
                )

            logger.debug(
                "Initializing Kafka producer with settings: %s", config.kafka_settings
            )
            # Create a copy of kafka_settings to avoid modifying the original
            kafka_settings = dict(config.kafka_settings)
            # Add value_serializer parameter
            kafka_settings["value_serializer"] = metric_serializer
            inst = KafkaProducer(**kafka_settings)
            logger.info("Kafka producer initialized successfully with value_serializer")
        except KafkaError as e:
            logger.error("Failed to create Kafka producer: %s", str(e))
            raise KafkaConnectionException(
                f"Failed to connect to Kafka: {str(e)}"
            ) from e
        except Exception as e:
            logger.error("Unexpected error creating Kafka producer: %s", str(e))
            raise KafkaConfigurationException(
                f"Error configuring Kafka producer: {str(e)}"
            ) from e
    return inst


def close_producer(timeout_seconds: int | None = None) -> None:
    """
    Close the Kafka producer if it exists.

    Args:
        timeout_seconds: Optional timeout in seconds to wait for the producer to flush pending messages and close. If None, waits indefinitely.
    """
    global inst
    if inst is not None:
        try:
            logger.info("Closing Kafka producer")
            inst.close(
                timeout=timeout_seconds
            )  # Closing also flushes any pending messages
            inst = None
            logger.info("Kafka producer closed successfully")
        except Exception as e:
            logger.error("Error closing Kafka producer: %s", str(e))


def send_metrics(
    metrics: list[Metric], get_app_config: Callable[[], AppConfig] = get_config
) -> None:
    """
    Send metrics to Kafka.

    Each metric is sent with a key derived from the metric name and either the topic or principal.
    The function does not block until messages are sent - it queues them for sending and returns immediately.

    Args:
        metrics: List of metrics to send.
        get_app_config: Function to retrieve the application configuration. Mainly for testing purposes.

    Raises:
        KafkaSendException: If there's an issue sending metrics to Kafka.
        KafkaProducerException: For other unexpected errors.
    """
    if not metrics:
        logger.warning("No metrics to send")
        return

    try:
        producer = get_kafka_producer(get_app_config)
        topic = get_app_config().metrics_topic

        if not topic:
            raise KafkaConfigurationException("Metrics topic is not configured")

        logger.debug("Sending %d metrics to topic %s", len(metrics), topic)

        metric_counts: dict[str, int] = {}
        for metric in metrics:
            # Create a key from metric name and topic/principal
            key = None
            if "topic" in metric.tags:
                key = f"{metric.name}:{metric.tags['topic']}".encode("utf-8")
            elif "principal" in metric.tags:
                key = f"{metric.name}:{metric.tags['principal']}".encode("utf-8")

            # Send to Kafka with key - metric will be serialized by value_serializer
            metric_counts[metric.name] = metric_counts.get(metric.name, 0) + 1
            producer.send(topic, value=metric, key=key, timestamp_ms=metric.timestamp)
        producer.flush()
        for metric_name, count in metric_counts.items():
            logger.info("Sent %d values for metric: %s", count, metric_name)
    except KafkaTimeoutError as e:
        raise KafkaSendException(f"Timeout sending metric to Kafka: {str(e)}") from e
    except KafkaError as e:
        raise KafkaSendException(f"Error sending metric to Kafka: {str(e)}") from e
    except Exception as e:
        raise MetricSendException(f"Unexpected error sending metrics: {str(e)}") from e
