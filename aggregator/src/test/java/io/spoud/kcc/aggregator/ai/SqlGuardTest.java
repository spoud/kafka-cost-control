package io.spoud.kcc.aggregator.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The security boundary for model-authored SQL. These are the highest-value tests in the AI
 * feature: everything else degrades gracefully, but a hole here means untrusted SQL reaching
 * a live database.
 */
class SqlGuardTest {

    private static final int MAX_ROWS = 500;

    private final SqlGuard guard = new SqlGuard();

    // --- accepted ---------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1",
            "select * from aggregated_data",
            "  \n SELECT name FROM aggregated_data",
            "WITH t AS (SELECT 1 AS x) SELECT x FROM t",
            "SELECT context->>'application' AS app, sum(value) FROM aggregated_data GROUP BY 1",
            "SELECT time_bucket(INTERVAL 1 DAY, start_time) AS d, max(value) FROM aggregated_data GROUP BY d",
            "SELECT DISTINCT unnest(json_keys(context)) FROM aggregated_data",
    })
    void acceptsReadOnlyQueries(String sql) {
        assertThatCode(() -> guard.validate(sql, MAX_ROWS)).doesNotThrowAnyException();
    }

    @Test
    void acceptsReplaceStringFunction() {
        // REPLACE is a string function, not DDL. Blocking it would break legitimate queries.
        assertThatCode(() -> guard.validate(
                "SELECT replace(name, '.', '_') FROM aggregated_data", MAX_ROWS))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsGlobAsAnOperator() {
        // `name GLOB '...'` is pattern matching; only glob(...) as a table function reads files.
        assertThatCode(() -> guard.validate(
                "SELECT * FROM aggregated_data WHERE name GLOB 'orders.*'", MAX_ROWS))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOffsetWhichMerelyContainsSet() {
        assertThatCode(() -> guard.validate(
                "SELECT name FROM aggregated_data LIMIT 10 OFFSET 5", MAX_ROWS))
                .doesNotThrowAnyException();
    }

    // --- rejected: statement kind -----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO aggregated_data VALUES (1)",
            "UPDATE aggregated_data SET value = 0",
            "DELETE FROM aggregated_data",
            "DROP TABLE aggregated_data",
            "CREATE TABLE evil (x INT)",
            "ALTER TABLE aggregated_data ADD COLUMN x INT",
            "TRUNCATE aggregated_data",
            "ATTACH 'other.db' AS other",
            "PRAGMA database_list",
            "SET memory_limit = '1GB'",
            "CALL pragma_version()",
            "INSTALL httpfs",
            "LOAD httpfs",
            "BEGIN TRANSACTION",
            "COPY aggregated_data TO '/tmp/leak.csv'",
            "EXPORT DATABASE '/tmp/dump'",
    })
    void rejectsNonReadOnlyStatements(String sql) {
        assertThatThrownBy(() -> guard.validate(sql, MAX_ROWS))
                .isInstanceOf(SqlGuard.RejectedException.class);
    }

    @Test
    void rejectsForbiddenKeywordHiddenInASubquery() {
        // "starts with SELECT" alone is not enough — the payload is nested.
        assertThatThrownBy(() -> guard.validate(
                "SELECT * FROM (COPY aggregated_data TO '/tmp/x.csv')", MAX_ROWS))
                .isInstanceOf(SqlGuard.RejectedException.class)
                .hasMessageContaining("COPY");
    }

    @Test
    void rejectsFilesystemReadingTableFunctions() {
        assertThatThrownBy(() -> guard.validate(
                "SELECT * FROM read_csv('/etc/passwd')", MAX_ROWS))
                .isInstanceOf(SqlGuard.RejectedException.class)
                .hasMessageContaining("READ_CSV");
    }

    // --- rejected: statement stacking -------------------------------------------------------

    @Test
    void rejectsStackedStatements() {
        assertThatThrownBy(() -> guard.validate(
                "SELECT 1; DROP TABLE aggregated_data", MAX_ROWS))
                .isInstanceOf(SqlGuard.RejectedException.class)
                .hasMessageContaining("single statement");
    }

    @Test
    void allowsASingleTrailingSemicolon() {
        assertThatCode(() -> guard.validate("SELECT 1;", MAX_ROWS)).doesNotThrowAnyException();
    }

    // --- literal and comment awareness ------------------------------------------------------

    @Test
    void semicolonInsideAStringLiteralIsNotAStatementSeparator() {
        // A naive split on ';' would reject this perfectly legitimate query.
        String sql = "SELECT * FROM aggregated_data WHERE name = 'a;b'";
        assertThatCode(() -> guard.validate(sql, MAX_ROWS)).doesNotThrowAnyException();
    }

    @Test
    void keywordInsideAStringLiteralIsNotAKeyword() {
        String sql = "SELECT * FROM aggregated_data WHERE name = 'DROP TABLE aggregated_data'";
        assertThatCode(() -> guard.validate(sql, MAX_ROWS)).doesNotThrowAnyException();
    }

    @Test
    void escapedQuotesDoNotTerminateALiteralEarly() {
        // If '' were treated as a closing quote, the trailing text would be scanned as SQL
        // and "DELETE" would trip the denylist.
        String sql = "SELECT * FROM aggregated_data WHERE name = 'it''s a DELETE joke'";
        assertThatCode(() -> guard.validate(sql, MAX_ROWS)).doesNotThrowAnyException();
    }

    @Test
    void keywordHiddenBehindALineCommentIsStillRejected() {
        // The comment must not be able to smuggle a statement past the scanner.
        assertThatThrownBy(() -> guard.validate("SELECT 1 -- comment\n; DROP TABLE aggregated_data", MAX_ROWS))
                .isInstanceOf(SqlGuard.RejectedException.class);
    }

    @Test
    void commentedOutKeywordDoesNotCauseAFalseRejection() {
        assertThatCode(() -> guard.validate(
                "SELECT value /* not a real DELETE */ FROM aggregated_data", MAX_ROWS))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(
                "SELECT value FROM aggregated_data -- DROP TABLE aggregated_data", MAX_ROWS))
                .doesNotThrowAnyException();
    }

    @Test
    void dollarQuotedStringsAreTreatedAsLiterals() {
        assertThatCode(() -> guard.validate(
                "SELECT * FROM aggregated_data WHERE name = $tag$DROP TABLE x$tag$", MAX_ROWS))
                .doesNotThrowAnyException();
    }

    @Test
    void qualifiedColumnNamesAreNotMistakenForKeywords() {
        assertThatCode(() -> guard.validate("SELECT a.set FROM aggregated_data a", MAX_ROWS))
                .doesNotThrowAnyException();
    }

    // --- normalisation ----------------------------------------------------------------------

    @Test
    void appendsLimitWhenAbsent() {
        assertThat(guard.validate("SELECT * FROM aggregated_data", MAX_ROWS))
                .isEqualTo("SELECT * FROM aggregated_data LIMIT " + MAX_ROWS);
    }

    @Test
    void keepsAnExistingLimit() {
        assertThat(guard.validate("SELECT * FROM aggregated_data LIMIT 10", MAX_ROWS))
                .doesNotContain("LIMIT " + MAX_ROWS)
                .endsWith("LIMIT 10");
    }

    @Test
    void preservesStringLiteralsInTheExecutedSql() {
        // Literals are blanked for *scanning* only; what we run must keep them verbatim.
        assertThat(guard.validate("SELECT * FROM aggregated_data WHERE name = 'a;b'", MAX_ROWS))
                .contains("'a;b'");
    }

    @Test
    void stripsCommentsFromTheExecutedSql() {
        assertThat(guard.validate("SELECT 1 -- trailing comment", MAX_ROWS))
                .doesNotContain("trailing comment");
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> guard.validate("   ", MAX_ROWS))
                .isInstanceOf(SqlGuard.RejectedException.class);
        assertThatThrownBy(() -> guard.validate(null, MAX_ROWS))
                .isInstanceOf(SqlGuard.RejectedException.class);
    }
}
