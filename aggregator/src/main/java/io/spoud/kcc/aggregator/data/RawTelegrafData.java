package io.spoud.kcc.aggregator.data;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
public record RawTelegrafData(
    Instant timestamp, String name, Map<String, Object> fields, Map<String, String> tags) {}
