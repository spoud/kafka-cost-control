package io.spoud.kcc.aggregator.olap;

import io.spoud.kcc.olap.domain.tables.AggregatedData;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.graphql.NonNull;
import org.jooq.Record1;
import org.jooq.impl.DSL;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import static io.spoud.kcc.olap.domain.Tables.AGGREGATED_DATA;

@ApplicationScoped
public class ContextDataOlapRepository {

    private final OlapInfra olapInfra;

    public ContextDataOlapRepository(OlapInfra olapInfra) {
        this.olapInfra = olapInfra;
    }

    public @NonNull Set<@NonNull String> getAllExistingContextKeys() {
        return olapInfra.getDSLContext().map(dslContext -> {
            AggregatedData a = AGGREGATED_DATA.as("a");
            return dslContext
                    // can't get the typing right with unnest(json_keys(a.CONTEXT)) directly
                    .selectDistinct(DSL.field("unnest(json_keys({0}))", String.class, a.CONTEXT))
                    .from(a)
                    .fetch()
                    .stream()
                    .map(Record1::value1)
                    .collect(Collectors.toSet());
        }).orElse(Collections.emptySet());
    }
}
