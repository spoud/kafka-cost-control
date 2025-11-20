import datetime
import json
from typing import Dict, Optional, Union

from pydantic import BaseModel, field_validator


class MetricFields(BaseModel):
    value: int


class Metric(BaseModel):
    fields: MetricFields
    name: str
    tags: Dict[str, str]
    timestamp: int

    @field_validator("tags")
    @classmethod
    def validate_tags(cls, tags: Dict[str, str]) -> Dict[str, str]:
        # Ensure either 'topic' or 'principal' is in tags, but not both
        has_topic = "topic" in tags
        has_principal = "principal" in tags

        if (has_topic and has_principal) or (not has_topic and not has_principal):
            raise ValueError(
                "Either 'topic' or 'principal' must be in tags, but not both"
            )

        return tags

    def model_dump_json(self, **kwargs) -> str:
        """Serialize the model to JSON string."""
        return json.dumps(self.model_dump(), **kwargs)

    @classmethod
    def model_validate_json(
        cls, json_str: Union[str, bytes, bytearray], **kwargs
    ) -> "Metric":
        """Deserialize JSON string to model instance."""
        if isinstance(json_str, (bytes, bytearray)):
            json_str = json_str.decode()
        return cls.model_validate(json.loads(json_str), **kwargs)

    @classmethod
    def create_principal_metric(
        cls,
        principal: str,
        metric_name: str,
        value: int,
        timestamp: datetime.datetime,
        extra_tags: Optional[Dict[str, str]] = None,
    ) -> "Metric":
        tags = {**(extra_tags or {}), "principal": principal}
        return cls(
            fields=MetricFields(value=value),
            name=metric_name,
            tags=tags,
            timestamp=int(timestamp.timestamp() * 1000),
        )

    @classmethod
    def create_topic_metric(
        cls,
        topic: str,
        metric_name: str,
        value: int,
        timestamp: datetime.datetime,
        extra_tags: Optional[Dict[str, str]] = None,
    ) -> "Metric":
        tags = {**(extra_tags or {}), "topic": topic}
        return cls(
            fields=MetricFields(value=value),
            name=metric_name,
            tags=tags,
            timestamp=int(timestamp.timestamp() * 1000),
        )
