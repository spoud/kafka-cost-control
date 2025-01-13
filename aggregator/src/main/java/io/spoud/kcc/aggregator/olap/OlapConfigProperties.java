package io.spoud.kcc.aggregator.olap;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import org.duckdb.DuckDBConnection;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration for the optional OLAP (online analytical processing) module.
 */
@ConfigMapping(prefix = "cc.olap")
public interface OlapConfigProperties {
    @WithName("enabled")
    @WithDefault("false")
    boolean enabled();

    /**
     * List of keys in the context object of a metric. Values corresponding to these keys
     * will be used to generate the primary key when ingesting the metric as row into the OLAP
     * database. If omitted, no part of the context will participate in the primary key generation.
     * If set, a special "cost_center" column will be populated with a concatenation of the values
     * corresponding to these keys.
     * <p>
     * If some context object is missing a particular key, then a fallback will be applied.
     * The fallback value is resolved by first looking for a fallback specific for that key.
     * If none is found, a global fallback value is applied (e.g. "unknown").
     *
     * @return the list of keys to use for the primary key
     */
    @WithName("context.cost-center-keys")
    Optional<Set<String>> costCenterKeys();

    /**
     * Fallback value for the context key in case the key is missing in the context object.
     *
     * @return the global fallback value
     */
    @WithName("context.global-value-fallback")
    @WithDefault("unknown")
    String globalContextValueFallback();

    /**
     * Fallback values for specific keys in the context object in case the key is missing in the context object.
     *
     * @return the key-specific fallback values
     */
    @WithName("context.key-specific-value-fallback")
    Map<String, String> keySpecificFallback();

    @WithName("database.url")
    @WithDefault("jdbc:duckdb:")
    String databaseUrl();

    @WithName("database.table")
    @WithDefault("aggregated_data")
    String databaseTable();

    @WithName("database.schema")
    @WithDefault(DuckDBConnection.DEFAULT_SCHEMA)
    String databaseSchema();

    /**
     * Writes to DB will be flushed once every this many seconds. This is a performance optimization that ensures
     * that data is batched and written to DB in larger chunks, which is better for DuckDB.
     *
     * @return the flush interval in seconds
     */
    @WithName("database.flush-interval.seconds")
    @WithDefault("5")
    int databaseFlushIntervalSeconds();

    /**
     * The maximum number of rows to buffer before flushing to the database.
     *
     * @return the maximum number of rows to buffer
     */
    @WithName("database.max-buffered-rows")
    @WithDefault("1000")
    int databaseMaxBufferedRows();

    /**
     * The fully qualified table name in the format "schema.table".
     * @return the fully qualified table name
     */
    default String fqTableName() {
        return databaseSchema() + "." + databaseTable();
    }

    /**
     * List of context keys. Each key in this list is expected to have a value that is a comma-separated list.
     * If this list is not empty, then the input metric will be split into multiple metrics, one for each value
     * in the comma-separated list. The context key will be replaced with an element of the list. The original
     * value of the metric will be divided by the number of elements in the list.
     * <p>
     * For example, say transformations.split-by = "readers,writers". Then consider a metric with value 100 and
     * the following context:
     * {
     *     "readers": "alice,bob",
     *     "writers": "claire,dave,eve"
     * }
     * This metric will be split into 5 different metrics (the original metric will be discarded):
     * Two metrics will have a value of 50 each (100 / 2) and the following contexts:
     * { "readers": "alice", "writers": null },
     * { "readers": "bob", "writers": null }
     * <p>
     * And three metrics will have a value of 33.33 each (100 / 3) and the following contexts:
     * { "readers": null, "writers": "claire" },
     * { "readers": null, "writers": "dave" },
     * { "readers": null, "writers": "eve" }
     * <p>
     * This is especially handy when you know that certain resources have been collectively used by
     * several cost centers (e.g. alice and bob) but you do not have the exact breakdown of the usage, so you use
     * the split-by transformation to distribute the usage evenly. You then use the cost-center-keys to mark the
     * "readers" and "writers" as the keys referencing the cost centers.
     * This way, you could, for example, easily query the total network ingress traffic for
     * "alice" by filtering for "writers" = "alice".
     * <p>
     * It is *very* important that you include the split-by keys in the cost-center-keys list. If the keys you split
     * by are not included in the cost-center-keys list, then the generated metrics will all have the same primary
     * key, which will cause them to overwrite each other in the OLAP database.
     *
     */
    @WithName("transformations.split-by")
    Optional<List<String>> splitBy();
}
