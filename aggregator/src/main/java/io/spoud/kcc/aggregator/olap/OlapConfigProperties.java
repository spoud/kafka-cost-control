package io.spoud.kcc.aggregator.olap;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import org.duckdb.DuckDBConnection;

/**
 * Configuration for the optional OLAP (online analytical processing) module.
 */
@ConfigMapping(prefix = "cc.olap")
public interface OlapConfigProperties {
    @WithName("enabled")
    @WithDefault("false")
    boolean enabled();

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
}
