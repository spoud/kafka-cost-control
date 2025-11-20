# Confluent Scraping Agent

A service that collects metrics from Confluent Cloud and publishes them to a Kafka topic. The agent runs on an hourly schedule and collects the following metrics for each configured Kafka cluster:

- `io.confluent.kafka.server/request_bytes` (grouped by principal)
- `io.confluent.kafka.server/response_bytes` (grouped by principal)
- `io.confluent.kafka.server/retained_bytes` (grouped by topic)

## Installation

### Prerequisites

- `uv` is installed
- Access to Confluent Cloud with API keys (the corresponding principal must be allowed to access the metrics API)
- Access to a Kafka cluster for publishing metrics

### Setup

1. Install Python 3.13, create a virtual environment and install dependencies:
   ```bash
   uv python install 3.13
   uv sync
   ```

1. Install git hooks:
   ```bash
   uv run pre-commit install
   ```

1. Configure the application (see Configuration section below)

## Configuration

The application is configured using environment variables.

### Required Configuration

| Environment Variable | Description |
|----------------------|-------------|
| `CONFLUENT_METRICS_API_KEY` | API key for Confluent Cloud |
| `CONFLUENT_METRICS_API_SECRET` | API secret for Confluent Cloud |
| `CONFLUENT_METRICS_CLUSTER_ID_LIST` | Comma-separated list of Confluent Cloud cluster IDs to scrape metrics from |

### Optional Configuration

| Environment Variable     | Description                                                                                       | Default Value |
|--------------------------|---------------------------------------------------------------------------------------------------|---------------|
| `CONFLUENT_METRICS_HOST` | Confluent Cloud API host                                                                          | `https://api.telemetry.confluent.cloud` |
| `METRICS_TOPIC`          | Kafka topic to which the scraped metrics will be published                                        | `confluent-metrics` |
| `MINUTE_TO_SCRAPE_AT`    | Minute of each hour at which to scrape metrics (5-59)                                             | `5` |
| `GLOBAL_LOG_LEVEL`       | Log level. Must be one of these: "CRITICAL", "FATAL", "ERROR", "WARN", "WARNING", "INFO", "DEBUG" | `INFO` |

### Kafka Configuration

Kafka connection settings can be provided in two ways:

1. As a JSON object in the `KAFKA_SETTINGS` environment variable:
   ```
   KAFKA_SETTINGS={"bootstrap_servers": "localhost:9092", "security_protocol": "SASL_SSL", ...}
   ```

2. As individual environment variables with the `KAFKA_` prefix, e.g.:
   ```
   KAFKA_BOOTSTRAP_SERVERS=localhost:9092
   KAFKA_SECURITY_PROTOCOL=SASL_SSL
   KAFKA_SASL_MECHANISM=PLAIN
   KAFKA_SASL_USERNAME=your-username
   KAFKA_SASL_PASSWORD=your-password
   ```

Common Kafka configuration parameters:

| Environment Variable | Description | Example Value |
|----------------------|-------------|---------------|
| `KAFKA_BOOTSTRAP_SERVERS` | Comma-separated list of Kafka broker addresses | `localhost:9092` |
| `KAFKA_SECURITY_PROTOCOL` | Security protocol to use | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT`, `SASL_SSL` |
| `KAFKA_SASL_MECHANISM` | SASL mechanism to use | `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512` |
| `KAFKA_SASL_USERNAME` | SASL username | `your-username` |
| `KAFKA_SASL_PASSWORD` | SASL password | `your-password` |
| `KAFKA_SSL_CAFILE` | Path to CA certificate file | `/path/to/ca.pem` |

For a complete list of Kafka client configuration options, refer to the [kafka-python documentation](https://kafka-python.readthedocs.io/en/master/apidoc/KafkaProducer.html).
All of them can be overwritten by supplying the corresponding environment variable (with a `KAFKA_` prefix).

## Usage

### Running the Application

To run the application:

```bash
python main.py    # alternatively: uv run main.py
```

The application will start and schedule metric collection to run every hour at the configured minute (default: 5 minutes past the hour).

The included Docker Compose file can be used for local development. It sets up:
- A Kafka broker accessible at localhost:9092
- A setup service that can be used to perform arbitrary initialization tasks once the Kafka broker has started

To run the Docker Compose stack do:

```bash
docker compose up
```

### Running Tests

Make sure you have Docker installed and running, then do:

```bash
uv run python -m pytest
```

On Mac you might first need to point Testcontainers to the correct Docker socket:

```bash
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="unix://$HOME/.colima/default/docker.sock"
```

## Metrics Format

The metrics are published to Kafka in JSON format with the following structure:

```json
{
  "fields": {
    "value": 721
  },
  "name": "confluent_kafka_server_retained_bytes",
  "tags": {
    "cluster_id": "lkc-cl23j",
    "topic": "test-topic"
  },
  "timestamp": 1756900800000
}
```

Each metric is sent with a key derived from the metric name and either the topic or principal, ensuring that related metrics are sent to the same partition.
