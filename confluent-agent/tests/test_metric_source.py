import datetime
import json
import os
import sys
from urllib.parse import urlencode

import pytest
from pydantic import SecretStr

from confluent_scraping_agent.config import AppConfig

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import base64

import requests
import responses

from confluent_scraping_agent.metric_source import (
    MetricQueryError,
    get_metrics_for_last_hour,
)

username = "test"
password = "hunter2"
basic_auth = base64.b64encode(f"{username}:{password}".encode()).decode()


def gen_failure_callback(
    error_status_codes: list[int] = [
        500,
    ],
    success_status=200,
    success_body_json=None,
):
    if success_body_json is None:
        success_body_json = {}
    call_count = {"count": 0}

    def callback(request):
        if call_count["count"] < len(error_status_codes):
            status = error_status_codes[call_count["count"]]
            call_count["count"] += 1
            return status, {}, ""
        else:
            return success_status, {}, json.dumps(success_body_json)

    return callback


import logging

root = logging.getLogger()
handler = logging.StreamHandler(sys.stdout)
root.setLevel(logging.INFO)
handler.setLevel(logging.INFO)
formatter = logging.Formatter("%(asctime)s - %(name)s - %(levelname)s - %(message)s")
handler.setFormatter(formatter)
root.addHandler(handler)


@responses.activate()
def test_query_single_page():
    with open("tests/request.json") as f:
        body = f.read()
        req_body = json.loads(body)
    with open("tests/resp-page-2.json") as f:
        resp_body = f.read()
        resp_json = json.loads(resp_body)

    # Fail first two times, then succeed
    for st in [500, 429]:
        responses.post(
            "https://api.telemetry.confluent.cloud/v2/metrics/cloud/query",
            json={},
            status=st,
        )
    responses.post(
        "https://api.telemetry.confluent.cloud/v2/metrics/cloud/query",
        json=resp_json,
        status=200,
    )

    # catch any additional calls and return 404
    responses.add_callback(
        url="https://api.telemetry.confluent.cloud/v2/metrics/cloud/query",
        callback=lambda x: (404, {}, ""),
        method="GET",
    )
    responses.add_callback(
        url="https://api.telemetry.confluent.cloud/v2/metrics/cloud/query",
        callback=lambda x: (404, {}, ""),
        method="POST",
    )

    now1 = datetime.datetime.strptime(
        "2025-09-02T01:00:00-00:00", "%Y-%m-%dT%H:%M:%S%z"
    )

    config = AppConfig.model_construct(
        confluent_metrics_api_key=SecretStr(username),
        confluent_metrics_api_secret=SecretStr(password),
        confluent_metrics_cluster_ids=["cluster-1"],
    )
    metrics = get_metrics_for_last_hour(
        get_app_config=lambda: config, now_func=lambda: now1
    )

    assert len(metrics) == 2


@responses.activate()
def test_query_fatal_error():
    # 400 - Bad request is not a retryable error, so we expect the function to raise an exception
    responses.add_callback(
        url="https://api.telemetry.confluent.cloud/v2/metrics/cloud/query",
        callback=lambda x: (403, {}, ""),
        method="POST",
    )

    now1 = datetime.datetime.strptime(
        "2025-09-02T01:00:00-00:00", "%Y-%m-%dT%H:%M:%S%z"
    )

    config = AppConfig.model_construct(
        confluent_metrics_api_key=SecretStr(username),
        confluent_metrics_api_secret=SecretStr(password),
        confluent_metrics_cluster_ids=["cluster-1"],
    )
    with pytest.raises(MetricQueryError):
        get_metrics_for_last_hour(get_app_config=lambda: config, now_func=lambda: now1)


@responses.activate
def test_query_multipage():
    with open("tests/request.json") as f:
        body = f.read()
        req_body = json.loads(body)
    with open("tests/resp-page-1.json") as f:
        resp_body = f.read()
        resp_json_1 = json.loads(resp_body)
    with open("tests/resp-page-2.json") as f:
        resp_body = f.read()
        resp_json_2 = json.loads(resp_body)

    # Register via 'Response' object
    rsp1 = responses.Response(
        method="POST",
        url="https://api.telemetry.confluent.cloud/v2/metrics/cloud/query",
        json=resp_json_1,
        status=200,
        match=[
            responses.matchers.json_params_matcher(req_body),
            responses.matchers.header_matcher({"Authorization": f"Basic {basic_auth}"}),
        ],
    )
    rsp2 = responses.Response(
        method="POST",
        url=f"https://api.telemetry.confluent.cloud/v2/metrics/cloud/query",
        status=200,
        json=resp_json_2,
        match=[
            responses.matchers.json_params_matcher(req_body),
            responses.matchers.header_matcher({"Authorization": f"Basic {basic_auth}"}),
            responses.matchers.query_param_matcher(
                {"page_token": resp_json_1["meta"]["pagination"]["next_page_token"]}
            ),
        ],
    )
    responses.add(rsp1)
    responses.add(rsp2)

    # catch any additional calls and return 404
    responses.add_callback(
        url="https://api.telemetry.confluent.cloud/v2/metrics/cloud/query",
        callback=lambda x: (404, {}, ""),
        method="GET",
    )
    responses.add_callback(
        url="https://api.telemetry.confluent.cloud/v2/metrics/cloud/query",
        callback=lambda x: (404, {}, ""),
        method="POST",
    )

    now1 = datetime.datetime.strptime(
        "2025-09-02T01:00:00-00:00", "%Y-%m-%dT%H:%M:%S%z"
    )

    config = AppConfig.model_construct(
        confluent_metrics_api_key=SecretStr(username),
        confluent_metrics_api_secret=SecretStr(password),
        confluent_metrics_cluster_ids=["cluster-1"],
    )
    metrics = get_metrics_for_last_hour(
        get_app_config=lambda: config, now_func=lambda: now1
    )

    assert len(metrics) == 12
