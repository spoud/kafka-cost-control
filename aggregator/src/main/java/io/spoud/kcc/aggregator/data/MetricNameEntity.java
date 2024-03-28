package io.spoud.kcc.aggregator.data;

import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;

public record MetricNameEntity(@NonNull String metricName,@NonNull  Instant lastSeen, @NonNull String aggregationType) {}
