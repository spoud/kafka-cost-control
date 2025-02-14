package io.spoud.kcc.aggregator.olap;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

/**
 * Configuration for the optional OLAP (online analytical processing) module.
 */
@ConfigMapping(prefix = "cc.olap")
public interface OlapConfigProperties {
    @WithName("enabled")
    @WithDefault("false")
    boolean enabled();

    /**
     * The JDBC URL to connect to the DuckDB database. To use an in-memory database, use "jdbc:duckdb:".
     * Otherwise, specify the path to the database file, e.g. "jdbc:duckdb:/path/to/database.db".
     */
    @WithName("database.url")
    @WithDefault("jdbc:duckdb:")
    String databaseUrl();

    /**
     * Writes to DB will be flushed once every this many seconds. This is a performance optimization that ensures
     * that data is batched and written to DB in larger chunks, which is better for DuckDB.
     *
     * @return the flush interval in seconds
     */
    @WithName("database.flush-interval.seconds")
    @WithDefault("15")
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
     * If set to true, the database will be seeded with some initial data when the application starts.
     * This is useful for development and testing. The file format is automatically detected.
     * Typical formats are CSV, JSON and Parquet.
     *
     * @return path to the data to load
     */
    @WithName("database.seed-data-path")
    Optional<String> databaseSeedDataPath();
}
