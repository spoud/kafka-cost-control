import logging
import sys
import time

import schedule

from confluent_scraping_agent.config import get_config
from confluent_scraping_agent.metric_output import MetricSendException, send_metrics
from confluent_scraping_agent.metric_source import (
    MetricQueryError,
    get_metrics_for_last_hour,
)


def collect_metrics():
    root.info("Collecting metrics...")
    try:
        metrics = get_metrics_for_last_hour()
        send_metrics(metrics)
    except MetricQueryError as e:
        root.error(f"Failed to collect metrics", exc_info=e)
    except MetricSendException as e:
        root.error(f"Failed to send metrics", exc_info=e)
    except Exception as e:
        root.error(f"Unexpected error during metric collection or sending", exc_info=e)


if __name__ == "__main__":
    # Configure Logging
    root = logging.getLogger()
    handler = logging.StreamHandler(sys.stdout)
    root.setLevel(logging.INFO)
    handler.setLevel(logging.INFO)
    formatter = logging.Formatter(
        "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    )
    handler.setFormatter(formatter)
    root.addHandler(handler)

    config = get_config()

    handler.setLevel(config.global_log_level)
    root.setLevel(config.global_log_level)

    scrape_time = f":{str(config.minute_to_scrape_at).rjust(2, "0")}"
    root.info(f"Scraping metrics every hour at XX{scrape_time}")
    schedule.every().hour.at(scrape_time).do(collect_metrics)
    while True:
        schedule.run_pending()
        time.sleep(1)
