import json
import logging
import os
import re
from typing import Any, Literal

from pydantic import Field, SecretStr, ValidationError, field_validator
from pydantic_settings import BaseSettings, EnvSettingsSource


class CustomSettingsSource(EnvSettingsSource):
    def prepare_field_value(
        self, field_name: str, field: Any, value: Any, value_is_complex: bool
    ) -> Any:
        # allow comma-separated list parsing
        if field_name == "confluent_metrics_cluster_ids":
            return value.split(",") if value else None

        return super().prepare_field_value(field_name, field, value, value_is_complex)


class AppConfig(BaseSettings):
    confluent_metrics_host: str = Field(
        default="https://api.telemetry.confluent.cloud", alias="CONFLUENT_METRICS_HOST"
    )
    confluent_metrics_api_key: SecretStr = Field(alias="CONFLUENT_METRICS_API_KEY")
    confluent_metrics_api_secret: SecretStr = Field(
        alias="CONFLUENT_METRICS_API_SECRET"
    )
    confluent_metrics_cluster_ids: list[str] = Field(
        alias="CONFLUENT_METRICS_CLUSTER_ID_LIST",
        description="Comma-separated list of Confluent Cloud cluster IDs to scrape metrics from.",
    )

    kafka_settings: dict[str, Any] = Field(
        default_factory=dict,
        alias="KAFKA_SETTINGS",
        description="Dictionary of Kafka settings collected from environment variables starting with 'KAFKA_'",
    )
    metrics_topic: str = Field(
        default="confluent-metrics",
        alias="METRICS_TOPIC",
        description="Kafka topic to which the scraped metrics will be published.",
    )

    minute_to_scrape_at: int = Field(
        default=5,
        alias="MINUTE_TO_SCRAPE_AT",
        ge=5,
        le=59,
        description="Minute of each hour at which to scrape metrics (5-59). May not be less than 5 to allow for metric availability delay.",
    )
    global_log_level: Literal[
        "CRITICAL", "FATAL", "ERROR", "WARN", "WARNING", "INFO", "DEBUG"
    ] = Field(
        alias="global_log_level",
        default="INFO",
        description="Global log level for the application. Will be set on the root logger and inherited by all loggers. ",
    )

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls,
        init_settings,
        env_settings,
        dotenv_settings,
        file_secret_settings,
    ):
        return (
            init_settings,
            CustomSettingsSource(settings_cls),
            dotenv_settings,
            file_secret_settings,
        )

    @field_validator("confluent_metrics_cluster_ids", mode="before")
    @classmethod
    def split_cluster_ids(cls, v: str | list[str]) -> list[str]:
        if isinstance(v, str):
            return [item.strip() for item in v.split(",") if item.strip()]
        return v

    @field_validator("kafka_settings", mode="before")
    @classmethod
    def collect_kafka_settings(cls, v: dict[str, Any] | str | None) -> dict:
        if isinstance(v, str):
            v = json.loads(v)
            assert isinstance(
                v, dict
            ), "KAFKA_SETTINGS must be a JSON object if provided as a string"
            return v
        if isinstance(v, dict) and len(v) > 0:
            return v
        # Collect all environment variables that start with 'KAFKA_' (case-insensitive)
        kafka_env_vars = {}
        for key, value in os.environ.items():
            if re.match(r"^KAFKA_", key, re.IGNORECASE):
                # Strip the 'KAFKA_' prefix and convert to lowercase
                new_key = re.sub(r"^KAFKA_", "", key, flags=re.IGNORECASE).lower()
                kafka_env_vars[new_key] = int(value) if value.isdigit() else value

        # Assign the collected environment variables to kafka_settings
        return kafka_env_vars


_inst = None

_logger = logging.getLogger(__name__)


def get_config() -> AppConfig:
    global _inst
    if _inst is None:
        try:
            _inst = AppConfig()
            _logger.info(
                "Configuration loaded successfully: %s", _inst.model_dump_json(indent=2)
            )
        except ValidationError as e:
            # Create a sanitized error message that doesn't expose secret values
            error_details = []
            for error in e.errors():
                # Only include the field name and error type, not the actual values
                error_msg = f"{error['loc'][0]}: {error['type']}"
                error_details.append(error_msg)

            sanitized_error = (
                f"Configuration validation error: {', '.join(error_details)}"
            )
            _logger.error(sanitized_error)
            exit(1)

    return _inst


if __name__ == "__main__":
    # For testing purposes, load the configuration when this module is run directly
    import sys

    root = logging.getLogger()
    root.setLevel(logging.DEBUG)
    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.INFO)
    formatter = logging.Formatter(
        "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    )
    handler.setFormatter(formatter)
    root.addHandler(handler)
    get_config()
