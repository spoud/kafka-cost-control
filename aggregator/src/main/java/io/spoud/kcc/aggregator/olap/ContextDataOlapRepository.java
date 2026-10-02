package io.spoud.kcc.aggregator.olap;

import io.spoud.kcc.aggregator.data.UnassignedEntity;
import io.spoud.kcc.data.EntityType;
import io.spoud.kcc.olap.domain.tables.AggregatedData;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.graphql.NonNull;
import org.jooq.Record1;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

    /**
     * Topics and principals seen in the period that are still unassigned: their latest window has no
     * value for {@code contextKey}. An entity a rule was added for drops out as soon as its next
     * window is processed, although its older rows keep no value. Cost and metrics count only the
     * unassigned rows, most expensive first. Rows without an entity (cluster-wide metrics) can't be
     * assigned by a rule and are left out.
     */
    public @NonNull List<@NonNull UnassignedEntity> unassignedEntities(Instant from, Instant to, String contextKey) {
        return olapInfra.getDSLContext().map(dslContext -> {
            AggregatedData a = AGGREGATED_DATA.as("a");
            // parenthesized: DuckDB would read `context->>key IS NULL` as `context->>(key IS NULL)`
            var value = DSL.field("({0}->>{1})", String.class, a.CONTEXT, DSL.val(contextKey));
            var condition = a.START_TIME.ge(from.atOffset(ZoneOffset.UTC))
                    .and(a.NAME.ne(""))
                    .and(a.ENTITY_TYPE.in(EntityType.TOPIC.name(), EntityType.PRINCIPAL.name()));
            if (to != null) {
                condition = condition.and(a.END_TIME.le(to.atOffset(ZoneOffset.UTC)));
            }
            var metrics = DSL.field("string_agg(DISTINCT {0}, ',' ORDER BY {0}) FILTER (WHERE {1} IS NULL)",
                    String.class, a.INITIAL_METRIC_NAME, value);
            var cost = DSL.coalesce(DSL.sum(a.COST).filterWhere(value.isNull()), BigDecimal.ZERO);
            var lastSeen = DSL.max(a.END_TIME);
            var lastAssigned = DSL.max(a.END_TIME).filterWhere(value.isNotNull());
            return dslContext
                    .select(a.ENTITY_TYPE, a.NAME, metrics, cost, lastSeen)
                    .from(a)
                    .where(condition)
                    .groupBy(a.ENTITY_TYPE, a.NAME)
                    .having(lastAssigned.isNull().or(lastAssigned.lt(lastSeen)))
                    .orderBy(cost.desc(), a.NAME)
                    .fetch()
                    .map(r -> new UnassignedEntity(
                            EntityType.valueOf(r.value1()),
                            r.value2(),
                            Arrays.asList(r.value3().split(",")),
                            r.value4().doubleValue(),
                            r.value5().toInstant()));
        }).orElse(List.of());
    }
}
