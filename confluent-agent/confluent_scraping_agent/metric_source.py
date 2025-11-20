import datetime
import logging
import re
from enum import Enum
from typing import Callable, List, Optional

import requests
from pydantic import BaseModel
from tenacity import (
    before_sleep_log,
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
    wait_random,
)

from confluent_scraping_agent.config import AppConfig, get_config
from confluent_scraping_agent.metric import Metric

logger = logging.getLogger(__name__)


class FilterOperator(str, Enum):
    EQ = "EQ"


class MetricFilter(BaseModel):
    field: str
    op: FilterOperator
    value: str


class MetricAggregation(BaseModel):
    metric: str


class MetricRequest(BaseModel):
    aggregations: List[MetricAggregation]
    granularity: str
    filter: MetricFilter
    group_by: List[str]
    intervals: List[str]
    limit: int
    format: str


class PaginationMeta(BaseModel):
    page_size: int
    next_page_token: Optional[str] = None


class ResponseMeta(BaseModel):
    pagination: Optional[PaginationMeta] = None


class MetricDataPoint(BaseModel):
    timestamp: str
    value: int

    # Dynamic fields based on group_by
    # We'll use model_validate to handle these
    model_config = {
        "extra": "allow",
    }


class MetricResponse(BaseModel):
    data: List[MetricDataPoint]
    meta: Optional[ResponseMeta] = None


def get_metrics_for_last_hour(
    get_app_config: Callable[[], AppConfig] = get_config,
    now_func: Callable[[], datetime.datetime] = lambda: datetime.datetime.now(
        datetime.timezone.utc
    ),
) -> list[Metric]:
    """Fetch metrics for the last hour.

    Calculates the current time (T) in UTC and fetches metrics for the time range [T-1h, T).
    The metrics are fetched for each cluster ID specified in the application configuration.
    The following metrics will be queried:
    - io.confluent.kafka.server/request_bytes grouped by metric.principal_id
    - io.confluent.kafka.server/response_bytes grouped by metric.principal_id
    - io.confluent.kafka.server/retained_bytes grouped by metric.topic

    Any 404 responses from the API are treated as empty results.

    Args:
        get_app_config: A callable that returns the application configuration. Mainly used for dependency injection in tests.
        now_func: A callable that returns the current datetime. Mainly used for dependency injection in tests.

    Returns:
        A list of Metric objects representing the fetched metrics.
    """
    config = get_app_config()
    now = now_func()
    now = now.replace(
        minute=0, second=0, microsecond=0
    )  # Round down to the nearest hour

    if now.tzinfo:
        now = now.astimezone(datetime.timezone.utc)
    else:
        raise ValueError("now_func must return a timezone-aware datetime!")

    # Format the interval string: PT1H/2025-09-02T01:00:00Z
    interval = f"PT1H/{now.strftime('%Y-%m-%dT%H:%M:%SZ')}"

    # Define the metrics to query
    principal_metrics = [
        "io.confluent.kafka.server/request_bytes",
        "io.confluent.kafka.server/response_bytes",
    ]
    topic_metrics = ["io.confluent.kafka.server/retained_bytes"]

    all_metrics = []

    # Get metrics for each cluster ID
    for cluster_id in config.confluent_metrics_cluster_ids:
        # Query principal metrics
        for metric_name in principal_metrics:
            metrics = _query_metric(
                config=config,
                cluster_id=cluster_id,
                metric_name=metric_name,
                group_by="metric.principal_id",
                interval=interval,
            )
            logger.info(
                "Fetched %d values for metric %s from cluster %s",
                len(metrics),
                metric_name,
                cluster_id,
            )
            all_metrics.extend(metrics)

        # Query topic metrics
        for metric_name in topic_metrics:
            metrics = _query_metric(
                config=config,
                cluster_id=cluster_id,
                metric_name=metric_name,
                group_by="metric.topic",
                interval=interval,
            )
            logger.info(
                "Fetched %d values for metric %s from cluster %s",
                len(metrics),
                metric_name,
                cluster_id,
            )
            all_metrics.extend(metrics)

    return all_metrics


class MetricQueryError(Exception):
    """Custom exception for metric query errors."""

    pass


class ApiError(MetricQueryError):
    """Custom exception for API errors."""

    def __init__(self, response):
        self.response = response
        super().__init__(f"API error {response.status_code}: {response.text}")


class RetryableResponseError(ApiError):
    """Custom exception for responses that should be retried."""

    pass


@retry(
    retry=retry_if_exception_type(RetryableResponseError),
    before_sleep=before_sleep_log(logger, logging.WARNING),
    stop=stop_after_attempt(5),
    wait=wait_exponential(multiplier=1, min=1, max=30) + wait_random(0, 3),
    reraise=True,
)
def _make_request_with_retry(method, url, **kwargs):
    """Make an HTTP request with retry logic.

    Args:
        method: The HTTP method to use (e.g., 'get', 'post').
        url: The URL to request.
        **kwargs: Additional arguments to pass to the request.

    Returns:
        The response object.

    Raises:
        requests.exceptions.HTTPError: If the request fails after all retries.
    """
    response = requests.request(method, url, **kwargs)

    # Handle 404 responses specially (don't retry, but don't raise)
    if response.status_code == 404:
        return response

    # For 5xx and 429 errors, raise an exception that will be caught and retried
    if 400 <= response.status_code < 600:
        if 500 <= response.status_code < 600 or response.status_code == 429:
            try:
                response.raise_for_status()
            except requests.exceptions.HTTPError as e:
                raise RetryableResponseError(response) from e
        else:
            # For other 4xx errors, raise the exception without retrying
            try:
                response.raise_for_status()
            except requests.exceptions.HTTPError as e:
                raise ApiError(response) from e

    # For other status codes, just return the response
    return response


def _query_metric(
    config: AppConfig, cluster_id: str, metric_name: str, group_by: str, interval: str
) -> list[Metric]:
    """Query a specific metric from the Confluent Cloud API.

    Args:
        config: The application configuration.
        cluster_id: The ID of the cluster to query.
        metric_name: The name of the metric to query.
        group_by: The field to group by (e.g., "metric.principal_id" or "metric.topic").
        interval: The time interval to query.

    Returns:
        A list of Metric objects.
    """
    # Create the request object
    request = MetricRequest(
        aggregations=[MetricAggregation(metric=metric_name)],
        granularity="PT1H",
        filter=MetricFilter(
            field="resource.kafka.id", op=FilterOperator.EQ, value=cluster_id
        ),
        group_by=[group_by],
        intervals=[interval],
        limit=1000,
        format="FLAT",
    )

    # Set up authentication
    auth = (
        config.confluent_metrics_api_key.get_secret_value(),
        config.confluent_metrics_api_secret.get_secret_value(),
    )

    # Make the initial POST request with retry
    url = f"{config.confluent_metrics_host}/v2/metrics/cloud/query"
    try:
        response = _make_request_with_retry(
            "post",
            url,
            json=request.model_dump(),
            auth=auth,
            headers={"Content-Type": "application/json"},
        )
    except Exception as e:
        raise MetricQueryError(
            f"Error querying metric {metric_name} for cluster {cluster_id}: {str(e)}"
        ) from e

    # Handle 404 responses as empty results
    if response.status_code == 404:
        return []

    # Print rate limit headers for debugging
    if response.headers.get("rateLimit-remaining") is not None:
        logger.debug(
            "Rate limit remaining: %s, reset in: %s seconds",
            response.headers.get("rateLimit-remaining"),
            response.headers.get("rateLimit-reset"),
        )
    else:
        logger.debug("No rate limit headers in response. Headers: %s", response.headers)

    # Parse the response
    metrics = []
    resp_data = MetricResponse.model_validate(response.json())
    metrics.extend(_process_response_data(resp_data, metric_name, group_by, cluster_id))

    # Handle pagination
    while (
        resp_data.meta
        and resp_data.meta.pagination
        and resp_data.meta.pagination.next_page_token
    ):
        params = {"page_token": resp_data.meta.pagination.next_page_token}
        try:
            response = _make_request_with_retry(
                "post",
                url,
                params=params,
                auth=auth,
                json=request.model_dump(),
                headers={"Content-Type": "application/json"},
            )
        except Exception as e:
            raise MetricQueryError(
                f"Error querying metric {metric_name} for cluster {cluster_id} with page token: {str(e)}"
            ) from e

        # Handle 404 responses as end of pagination
        if response.status_code == 404:
            break

        # Parse the response
        resp_data = MetricResponse.model_validate(response.json())
        metrics.extend(
            _process_response_data(resp_data, metric_name, group_by, cluster_id)
        )

    return metrics


def _process_response_data(
    response: MetricResponse, metric_name: str, group_by: str, cluster_id: str
) -> list[Metric]:
    """Process the response data and convert it to Metric objects.

    Args:
        response: The response data from the API.
        metric_name: The name of the metric.
        group_by: The field that was grouped by.

    Returns:
        A list of Metric objects.
    """
    metrics = []

    # Replace dots and slashes with underscores for metric naming
    metric_name = re.sub(r"[./]", "_", metric_name).replace("io_", "")

    for data_point in response.data:
        # Parse the timestamp
        timestamp = datetime.datetime.fromisoformat(
            data_point.timestamp.replace("Z", "+00:00")
        )

        # Get the value
        value = data_point.value

        # Get the group_by value
        if group_by == "metric.principal_id":
            # Create a principal metric
            principal = getattr(data_point, "metric.principal_id")
            metric = Metric.create_principal_metric(
                principal=principal,
                metric_name=metric_name,
                value=value,
                extra_tags={"cluster_id": cluster_id},
                timestamp=timestamp,
            )
        elif group_by == "metric.topic":
            # Create a topic metric
            topic = getattr(data_point, "metric.topic")
            metric = Metric.create_topic_metric(
                topic=topic,
                metric_name=metric_name,
                value=value,
                extra_tags={"cluster_id": cluster_id},
                timestamp=timestamp,
            )
        else:
            # Skip unknown group_by fields
            continue

        metrics.append(metric)

    return metrics
