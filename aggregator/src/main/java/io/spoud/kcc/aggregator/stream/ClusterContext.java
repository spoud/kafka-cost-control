package io.spoud.kcc.aggregator.stream;

import io.spoud.kcc.aggregator.CostControlConfigProperties;
import io.spoud.kcc.data.AggregatedData;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Puts the cluster a record came from into its context. The hourly key includes the context, so the
 * same topic or principal on two clusters stays two rows, and every view that groups or filters by
 * a context key can do so by cluster. The tags, where the raw record names its cluster, are cleared
 * before the grouping; the context is kept.
 */
public class ClusterContext {

    private final String tag;
    private final String contextKey;
    private final Optional<String> defaultCluster;
    private final Map<String, String> names;

    public ClusterContext(CostControlConfigProperties.Cluster config) {
        this.tag = config.tag();
        this.contextKey = config.contextKey();
        this.defaultCluster = config.defaultCluster().filter(name -> !name.isBlank());
        this.names = config.names().map(ClusterContext::parseNames).orElse(Map.of());
    }

    /**
     * The record with its cluster in the context, replacing a value a context rule set for the same
     * key. A record without the tag gets the default cluster, or keeps its context as it is.
     */
    public AggregatedData apply(AggregatedData data) {
        var cluster = Optional.ofNullable(data.getTags())
                .map(tags -> tags.get(tag))
                .filter(id -> !id.isBlank())
                .map(id -> names.getOrDefault(id, id))
                .or(() -> defaultCluster);
        if (cluster.isEmpty()) {
            return data;
        }
        var context = data.getContext() == null ? new HashMap<String, String>() : new HashMap<>(data.getContext());
        context.put(contextKey, cluster.get());
        data.setContext(context);
        return data;
    }

    /** {@code lkc-a1b2=confluent-prod,lkc-c3d4=confluent-dev} as a map from cluster ID to name. */
    static Map<String, String> parseNames(String value) {
        var names = new LinkedHashMap<String, String>();
        for (var entry : value.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            var parts = entry.split("=", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("cc.cluster.names must be id=name pairs separated by commas; '"
                        + entry.trim() + "' is not one");
            }
            if (names.put(parts[0].trim(), parts[1].trim()) != null) {
                throw new IllegalArgumentException("cc.cluster.names names the cluster '" + parts[0].trim() + "' twice");
            }
        }
        return names;
    }
}
