package io.spoud.kcc.aggregator.bills;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.graphql.Description;

import java.util.List;

/**
 * An other line as entered. Its own input type so {@code context} can be left out: SmallRye
 * makes a map input required, and only a CONTEXT line has a context.
 */
@RegisterForReflection
public record OtherLineRequest(
        String description,
        @Description("In dollars; negative for a credit") Double amount,
        OtherLine.Allocation allocation,
        @Description("For CONTEXT: the context the amount belongs to, e.g. [{key: \"tenant\", value: \"data-platform\"}]")
        List<ContextPair> context) {

    public record ContextPair(String key, String value) {
    }
}
