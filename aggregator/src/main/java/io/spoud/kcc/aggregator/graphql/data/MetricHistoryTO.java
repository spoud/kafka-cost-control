package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import java.time.Instant;
import java.util.List;

@Name("MetricHistory")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@RegisterForReflection
public class MetricHistoryTO {
    @NonNull
    private List<Instant> times;

    @NonNull
    private List<MetricHistoryNameTO> metrics;
}
