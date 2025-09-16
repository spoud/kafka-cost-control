package io.spoud.kcc.aggregator.olap;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;
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
     * The total memory limit avaiable to the aggregator. This is used to calculate the memory limit for the DuckDB database.
     * The value is expected to be in Mebibytes (MiB) (1 MiB = 1024 KiB, 1 KiB = 1024 bytes).
     * If not set, the `database.memory.limit.percent` property will have no effect.
     *
     * @return the total memory limit in MiB
     */
    @WithName("total.memory.limit.mb")
    Optional<Integer> totalMemoryLimitMb();

    /**
     * Maximum percentage of RAM that can be used by the DuckDB database.
     * Note that this limit will not be enforced unless the `total.memory.limit.mb` property is set.
     *
     * @return the percentage of RAM to use
     */
    @WithName("database.memory.limit.percent")
    @WithDefault("30")
    int databaseMemoryLimitPercent();

    /**
     * Calculate the memory limit for the DuckDB database based on the total memory limit and the percentage of RAM to use.
     * If the total memory limit is not set, this method will return an empty Optional.
     *
     * @return the memory limit for the DuckDB database in MiB (1 MiB = 1024 KiB, 1 KiB = 1024 bytes), returned value is rounded down to the nearest integer
     */
    default Optional<Integer> databaseMemoryLimitMib() {
        return totalMemoryLimitMb()
                .map(totalMemory -> totalMemory * (databaseMemoryLimitPercent() / 100.0))
                .map(Double::intValue);
    }

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
     * If set the database will be seeded with some initial data when the application starts.
     * This is useful for development and testing. The file format is automatically detected.
     * Typical formats are CSV, JSON and Parquet.
     *
     * @return path to the data to load
     */
    @WithName("database.seed-data-path")
    Optional<String> databaseSeedDataPath();

    /**
     * If set synthetic data will be inserted for this amount of past days from today,
     * e.g. to generate synthetic data for every day for the last month set to 31.
     * Used for development and testing purposes.
     */
    @WithName("database.insert-synthetic-days")
    Optional<Integer> insertSyntheticDays();

    /**
     * The number of days to retain data in the database. Data older than this will be automatically deleted.
     * @return the retention period in days
     */
    @WithName("database.retention.days")
    @WithDefault("90")
    int databaseRetentionDays();

    @WithName("database.retention.check-interval")
    @WithDefault("PT24H")
    Duration databaseRetentionCheckInterval();
}
