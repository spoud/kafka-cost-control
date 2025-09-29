import argparse
import json
import random
from datetime import datetime, timedelta
from kafka import KafkaProducer
import time

# Example metric
# {
#  "fields": {
#    "counter_diff": 12198
#  },
#  "name": "kafka_server_brokertopicmetrics_bytesout_total",
#  "tags": {
#    "env": "dev",
#    "pod_name": "poc-cluster-dual-role-2",
#    "topic": "kcc-test-aggregated"
#  },
#  "timestamp": 1746799800
# }

TOPIC_NAMES = [
    "accounting",
    "analytics",
    "api",
    "auth",
    "billing",
    "catalog",
    "checkout",
    "customer",
    "inventory",
    "loyalty",
    "orders",
    "payments",
    "products",
    "recommendations",
    "shipping",
    "subscriptions",
]


def parse_arguments():
    parser = argparse.ArgumentParser(description='Generate random metrics and write to Kafka')
    parser.add_argument('--start-date', type=str, required=True, help='Start date in format YYYY-MM-DD HH:MM:SS')
    parser.add_argument('--end-date', type=str, required=True, help='End date in format YYYY-MM-DD HH:MM:SS')
    parser.add_argument('--topic', type=str, required=True, help='Kafka topic to write metrics to')
    parser.add_argument('--bootstrap-servers', type=str, default='localhost:9092', help='Kafka bootstrap servers')
    return parser.parse_args()


def create_metric(topic_name, timestamp, metric_name="confluent_kafka_server_request_bytes"):
    """Create a metric for a specific topic and timestamp"""
    return {
        "fields": {
            "counter_diff": random.randint(1000, 50000)
        },
        "name": metric_name,
        "tags": {
            "env": "dev",
            "pod_name": "poc-cluster-dual-role-2",
            "topic": topic_name
        },
        "timestamp": int(timestamp.timestamp())
    }


def main():
    args = parse_arguments()

    # Parse start and end dates
    start_date = datetime.strptime(args.start_date, '%Y-%m-%d %H:%M:%S')
    end_date = datetime.strptime(args.end_date, '%Y-%m-%d %H:%M:%S')

    # Setup Kafka producer
    producer = KafkaProducer(
        bootstrap_servers=args.bootstrap_servers,
        value_serializer=lambda v: json.dumps(v).encode('utf-8')
    )

    # Iterate over time range in 15-minute increments
    current_time = start_date
    message_count = 0

    print(f"Generating metrics from {start_date} to {end_date} for {len(TOPIC_NAMES)} topics")

    try:
        while current_time <= end_date:
            for topic_name in TOPIC_NAMES:
                m1 = create_metric(topic_name, current_time, metric_name="confluent_kafka_server_response_bytes")
                m2 = create_metric(topic_name, current_time, metric_name="confluent_kafka_server_request_bytes")
                m3 = create_metric(topic_name, current_time, metric_name="kafka_topic_partition_count")
                m4 = create_metric(topic_name, current_time, metric_name="confluent_kafka_server_retained_bytes")
                # Set the Kafka record timestamp to the same timestamp used in the metric
                t = current_time.timestamp() * 1000
                producer.send(args.topic, m1, timestamp_ms=int(t))
                producer.send(args.topic, m2, timestamp_ms=int(t))
                producer.send(args.topic, m3, timestamp_ms=int(t))
                producer.send(args.topic, m4, timestamp_ms=int(t))
                message_count += 4

            # Move to next 15-minute step
            current_time += timedelta(minutes=15)

            # Progress output every 100 timestamps
            if message_count % (100 * len(TOPIC_NAMES)) == 0:
                print(f"Sent {message_count} messages, currently at {current_time}")

        # Make sure all messages are sent
        producer.flush()
        print(f"Successfully sent {message_count} metrics to topic '{args.topic}'")

    except Exception as e:
        print(f"Error sending messages: {e}")
    finally:
        producer.close()


if __name__ == "__main__":
    main()
