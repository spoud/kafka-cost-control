package io.spoud.kcc.operator.data;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
public record ContextDataSaveRequest(
        String id,
        Instant validFrom,
        Instant validUntil,
        EntityType entityType,
        String regex,
        Map<String, String> context) {
}
