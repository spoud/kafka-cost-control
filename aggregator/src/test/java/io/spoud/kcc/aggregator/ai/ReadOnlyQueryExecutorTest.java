package io.spoud.kcc.aggregator.ai;

import io.spoud.kcc.aggregator.olap.OlapConfigProperties;
import io.spoud.kcc.aggregator.olap.OlapInfra;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the containment contract of {@link ReadOnlyQueryExecutor} against a real DuckDB,
 * rather than a mock — the guarantees being tested are engine behaviour, so a mock would prove
 * nothing.
 */
class ReadOnlyQueryExecutorTest {

    private OlapInfra olapInfra;
    private ReadOnlyQueryExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        olapInfra = new OlapInfra(new FakeOlapConfig());
        olapInfra.init();
        seedRows(4);
        executor = new ReadOnlyQueryExecutor(olapInfra, new SqlGuard(), new FakeAiConfig());
    }

    @AfterEach
    void tearDown() throws Exception {
        olapInfra.getConnection().ifPresent(conn -> {
            try {
                conn.close();
            } catch (Exception ignored) {
                // best effort
            }
        });
    }

    @Test
    void duplicatedConnectionSeesTheSameInMemoryDatabase() throws Exception {
        // This is the specific trap: a fresh DriverManager connection to "jdbc:duckdb:" opens a
        // *different, empty* database. If duplicate() ever regressed to that, every model query
        // would silently return zero rows instead of failing loudly.
        try (Connection duplicated = olapInfra.duplicateConnection().orElseThrow();
             Statement stmt = duplicated.createStatement()) {
            var rs = stmt.executeQuery("SELECT count(*) FROM aggregated_data");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(4);
        }
    }

    @Test
    void executesAReadOnlyQuery() {
        var result = executor.execute("SELECT name, value FROM aggregated_data ORDER BY name LIMIT 2");

        assertThat(result.columns()).containsExactly("name", "value");
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void appendsTheRowCapAsALimitWhenTheQueryHasNone() {
        var result = executor.execute("SELECT name FROM aggregated_data");

        // FakeAiConfig caps at 2 rows, so the guard appends LIMIT 2.
        assertThat(result.executedSql()).endsWith("LIMIT 2");
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    void flagsTruncationSoAPartialResultIsNotReportedAsComplete() {
        // The query asks for more rows than the cap allows. The model must be told the result
        // was cut off, otherwise it will present a partial total as the full answer.
        var result = executor.execute("SELECT * FROM aggregated_data LIMIT 1000");

        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void reportsAnEmptyResultDistinctlyFromAFailure() {
        var result = executor.execute("SELECT * FROM aggregated_data WHERE name = 'does-not-exist'");

        assertThat(result.rows()).isEmpty();
        assertThat(result.columns()).isNotEmpty();
    }

    @Test
    void rejectsMutationsAndLeavesTheDataUntouched() {
        assertThatThrownBy(() -> executor.execute("DELETE FROM aggregated_data"))
                .isInstanceOf(SqlGuard.RejectedException.class);
        assertThatThrownBy(() -> executor.execute("DROP TABLE aggregated_data"))
                .isInstanceOf(SqlGuard.RejectedException.class);
        assertThatThrownBy(() -> executor.execute("UPDATE aggregated_data SET value = 0"))
                .isInstanceOf(SqlGuard.RejectedException.class);

        var surviving = executor.execute("SELECT count(*) AS c FROM aggregated_data");
        assertThat(surviving.rows().get(0).get(0)).isEqualTo("4");
    }

    @Test
    void rejectsAttemptsToWriteToTheFilesystem() {
        assertThatThrownBy(() -> executor.execute("COPY aggregated_data TO '/tmp/leak.csv'"))
                .isInstanceOf(SqlGuard.RejectedException.class);
        assertThatThrownBy(() -> executor.execute("SELECT * FROM read_csv('/etc/passwd')"))
                .isInstanceOf(SqlGuard.RejectedException.class);
    }

    @Test
    void surfacesSqlErrorsAsQueryFailures() {
        assertThatThrownBy(() -> executor.execute("SELECT no_such_column FROM aggregated_data"))
                .isInstanceOf(ReadOnlyQueryExecutor.QueryFailedException.class);
    }

    @Test
    void leavesTheSharedConnectionAbleToExport() throws Exception {
        // Regression: an earlier version set enable_external_access=false on the duplicated
        // connection as defence in depth. That setting is global to the DuckDB instance, not
        // per-connection, so it also killed COPY ... TO on the shared connection - permanently
        // breaking /olap/export after the first assistant query.
        executor.execute("SELECT name FROM aggregated_data");

        var out = java.nio.file.Files.createTempFile("export-probe", ".csv");
        java.nio.file.Files.deleteIfExists(out);
        Connection shared = olapInfra.getConnection().orElseThrow();
        try (Statement stmt = shared.createStatement()) {
            stmt.execute("COPY (SELECT * FROM aggregated_data) TO '" + out + "' (HEADER, DELIMITER ',')");
        }
        assertThat(out).exists();
        java.nio.file.Files.deleteIfExists(out);
    }

    @Test
    void reportsAClearErrorWhenOlapIsDisabled() {
        var disabled = new ReadOnlyQueryExecutor(
                new OlapInfra(new FakeOlapConfig() {
                    @Override
                    public boolean enabled() {
                        return false;
                    }
                }),
                new SqlGuard(),
                new FakeAiConfig());

        assertThatThrownBy(() -> disabled.execute("SELECT 1"))
                .isInstanceOf(ReadOnlyQueryExecutor.QueryFailedException.class)
                .hasMessageContaining("disabled");
    }

    private void seedRows(int count) throws Exception {
        Connection conn = olapInfra.getConnection().orElseThrow();
        try (var stmt = conn.prepareStatement(
                "INSERT INTO aggregated_data VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            Instant start = Instant.now().minus(Duration.ofHours(count));
            for (int i = 0; i < count; i++) {
                Instant rowStart = start.plus(Duration.ofHours(i));
                stmt.setObject(1, rowStart.atOffset(ZoneOffset.UTC));
                stmt.setObject(2, rowStart.plus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC));
                stmt.setString(3, "confluent_kafka_server_retained_bytes");
                stmt.setString(4, "TOPIC");
                stmt.setString(5, "topic-" + i);
                stmt.setObject(6, "{}");
                stmt.setString(7, "{\"application\":\"app-" + i + "\"}");
                stmt.setDouble(8, 100.0 * (i + 1));
                stmt.setString(9, "topic-" + i);
                stmt.setString(10, UUID.randomUUID().toString());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    /** In-memory DuckDB, no seeding, no memory cap fiddling. */
    private static class FakeOlapConfig implements OlapConfigProperties {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String databaseUrl() {
            return "jdbc:duckdb:";
        }

        @Override
        public int databaseFlushIntervalSeconds() {
            return 10;
        }

        @Override
        public int databaseMaxBufferedRows() {
            return 10;
        }

        @Override
        public Optional<Integer> totalMemoryLimitMb() {
            return Optional.of(512);
        }

        @Override
        public int databaseMemoryLimitPercent() {
            return 50;
        }

        @Override
        public Optional<String> databaseSeedDataPath() {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> insertSyntheticDays() {
            return Optional.empty();
        }
    }

    /** Deliberately tiny row cap so the truncation path is exercised. */
    private static class FakeAiConfig implements AiConfigProperties {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean privateMode() {
            return false;
        }

        @Override
        public int maxRows() {
            return 2;
        }

        @Override
        public int maxToolIterations() {
            return 4;
        }

        @Override
        public Duration queryTimeout() {
            return Duration.ofSeconds(5);
        }

        @Override
        public int maxSessions() {
            return 10;
        }

        @Override
        public int maxHistoryExchanges() {
            return 10;
        }
    }
}
