package io.spoud.kcc.aggregator.graphql.data;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

@Name("NameWithDefinition")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@RegisterForReflection
public class NameWithDefinitionTO {
    @NonNull
    private String name;
    private String definition;

    public static NameWithDefinitionTO withNoDefinition(String name) {
        return new NameWithDefinitionTO(name, name);
    }
}
