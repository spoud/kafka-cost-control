package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

import java.util.List;

@Name("MetricHistoryName")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@RegisterForReflection
public class MetricHistoryNameTO {
    @NonNull
    private String name;

    @NonNull
    private List<Double> values;
}
