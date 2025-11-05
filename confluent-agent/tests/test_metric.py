import datetime
import json

import pytest

from confluent_scraping_agent.metric import Metric, MetricFields


def build_metric(
    metric_name: str,
    value: int,
    tags: dict[str, str],
    timestamp: int,
    topic: str | None,
    principal_id: str | None,
) -> Metric:
    if (topic is not None and principal_id is not None) or (
        topic is None and principal_id is None
    ):
        raise ValueError("Either topic or principal_id must be provided")
    ts = datetime.datetime.fromtimestamp(timestamp / 1000.0)
    return (
        Metric.create_topic_metric(topic, metric_name, value, ts, tags)
        if topic
        else Metric.create_principal_metric(principal_id, metric_name, value, ts, tags)
    )


def test_metric_serialization():
    """Test that a Metric can be serialized to JSON and deserialized back to a Metric."""
    # Create a metric using the build_metric function
    metric = build_metric(
        metric_name="test_metric",
        value=42,
        tags={"env": "test", "service": "scraper"},
        timestamp=1630000000000,
        topic="test-topic",
        principal_id=None,
    )

    # Serialize to JSON
    json_str = metric.model_dump_json()

    # Verify it's valid JSON
    json_dict = json.loads(json_str)
    assert isinstance(json_dict, dict)

    # Verify the structure
    assert json_dict["name"] == "test_metric"
    assert json_dict["fields"]["value"] == 42
    assert json_dict["tags"]["env"] == "test"
    assert json_dict["tags"]["service"] == "scraper"
    assert json_dict["tags"]["topic"] == "test-topic"
    assert json_dict["timestamp"] == 1630000000000

    # Deserialize back to a Metric
    deserialized_metric = Metric.model_validate_json(json_str)

    # Verify it's a Metric instance
    assert isinstance(deserialized_metric, Metric)

    # Verify the values match
    assert deserialized_metric.name == "test_metric"
    assert deserialized_metric.fields.value == 42
    assert deserialized_metric.tags["env"] == "test"
    assert deserialized_metric.tags["service"] == "scraper"
    assert deserialized_metric.tags["topic"] == "test-topic"
    assert deserialized_metric.timestamp == 1630000000000


def test_metric_validation():
    """Test that Metric validation works correctly."""
    # Test that a metric with both topic and principal raises an error
    with pytest.raises(ValueError):
        Metric(
            fields=MetricFields(value=42),
            name="test_metric",
            tags={"env": "test", "topic": "test-topic", "principal": "test-principal"},
            timestamp=1630000000000,
        )

    # Test that a metric with neither topic nor principal raises an error
    with pytest.raises(ValueError):
        Metric(
            fields=MetricFields(value=42),
            name="test_metric",
            tags={"env": "test"},
            timestamp=1630000000000,
        )

    # Test that a metric with topic is valid
    metric = Metric(
        fields=MetricFields(value=42),
        name="test_metric",
        tags={"env": "test", "topic": "test-topic"},
        timestamp=1630000000000,
    )
    assert metric.tags["topic"] == "test-topic"

    # Test that a metric with principal is valid
    metric = Metric(
        fields=MetricFields(value=42),
        name="test_metric",
        tags={"env": "test", "principal": "test-principal"},
        timestamp=1630000000000,
    )
    assert metric.tags["principal"] == "test-principal"


def test_build_metric_function():
    """Test that the build_metric function works correctly."""
    # Test with topic
    metric = build_metric(
        metric_name="test_metric",
        value=42,
        tags={"env": "test"},
        timestamp=1630000000000,
        topic="test-topic",
        principal_id=None,
    )
    assert metric.name == "test_metric"
    assert metric.fields.value == 42
    assert metric.tags["env"] == "test"
    assert metric.tags["topic"] == "test-topic"
    assert metric.timestamp == 1630000000000

    # Test with principal_id
    metric = build_metric(
        metric_name="test_metric",
        value=42,
        tags={"env": "test"},
        timestamp=1630000000000,
        topic=None,
        principal_id="test-principal",
    )
    assert metric.tags["principal"] == "test-principal"

    # Test with both topic and principal_id (should raise ValueError)
    with pytest.raises(ValueError):
        build_metric(
            metric_name="test_metric",
            value=42,
            tags={"env": "test"},
            timestamp=1630000000000,
            topic="test-topic",
            principal_id="test-principal",
        )

    # Test with neither topic nor principal_id (should raise ValueError)
    with pytest.raises(ValueError):
        build_metric(
            metric_name="test_metric",
            value=42,
            tags={"env": "test"},
            timestamp=1630000000000,
            topic=None,
            principal_id=None,
        )
