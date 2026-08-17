package io.spoud.kcc.aggregator.ai;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates model-authored SQL before it is allowed anywhere near the database.
 * <p>
 * This is one of four independent layers (see {@link ReadOnlyQueryExecutor} for the other three:
 * an isolated connection, {@code enable_external_access=false}, and an unconditional rollback).
 * It is deliberately conservative — it rejects anything it does not positively recognise as a
 * single read-only query, and it is far cheaper to loosen later than to discover it was too
 * permissive.
 * <p>
 * The parsing is literal-aware rather than regex-over-raw-text, because the naive version is
 * defeated by a semicolon or a keyword inside a string literal — e.g.
 * {@code SELECT * FROM aggregated_data WHERE name = 'a;DROP TABLE x'} is a perfectly legitimate
 * single query that a raw split on ';' would reject, while
 * {@code SELECT 1; DROP TABLE aggregated_data} must be rejected.
 */
@ApplicationScoped
public class SqlGuard {

    /**
     * Statement kinds that must never appear, even in a subquery or CTE. DuckDB allows some of
     * these in positions a "starts with SELECT" check alone would not catch.
     */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            // mutation
            "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "UPSERT",
            // DDL. Note "REPLACE" is deliberately absent: it is a common string function
            // (REPLACE(name, 'a', 'b')), and "CREATE OR REPLACE" is already caught by CREATE.
            "CREATE", "DROP", "ALTER",
            // data movement / filesystem / network escape hatches
            "COPY", "EXPORT", "IMPORT", "ATTACH", "DETACH", "INSTALL", "LOAD",
            // engine control
            "PRAGMA", "SET", "RESET", "CALL", "CHECKPOINT", "VACUUM", "ANALYZE",
            "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "PREPARE", "EXECUTE", "DEALLOCATE");

    /**
     * Table functions that read or write outside the database file.
     * {@code enable_external_access=false} already blocks these at the engine level; rejecting
     * them here too gives a clearer error and defence in depth.
     * <p>
     * These are matched only in function position (followed by an open paren), so that names
     * which double as operators — notably {@code GLOB}, valid as {@code name GLOB '*.log'} —
     * are not false-positived.
     */
    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
            "READ_CSV", "READ_CSV_AUTO", "READ_PARQUET", "READ_JSON", "READ_JSON_AUTO",
            "READ_BLOB", "READ_TEXT", "GLOB", "PARQUET_SCAN", "CSV_SCAN", "ICEBERG_SCAN",
            "DELTA_SCAN", "POSTGRES_SCAN", "MYSQL_SCAN", "SQLITE_SCAN");

    private static final Pattern STARTS_WITH_SELECT_OR_WITH =
            Pattern.compile("^\\s*(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern HAS_LIMIT =
            Pattern.compile("\\bLIMIT\\s+\\d+", Pattern.CASE_INSENSITIVE);

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Thrown when SQL fails validation. The message is safe to show the model so it can retry. */
    public static class RejectedException extends RuntimeException {
        public RejectedException(String message) {
            super(message);
        }
    }

    /**
     * Validate a model-authored query and return it normalised (comments stripped, single
     * trailing semicolon removed, {@code LIMIT} appended if absent).
     *
     * @throws RejectedException if the SQL is anything other than a single read-only query
     */
    public String validate(String sql, int maxRows) {
        if (sql == null || sql.isBlank()) {
            throw new RejectedException("Empty query.");
        }

        String stripped = stripCommentsAndBlankLiterals(sql);

        if (containsStatementSeparator(stripped)) {
            throw new RejectedException(
                    "Only a single statement is allowed; found more than one (';' outside a string literal).");
        }

        // Work on the comment-stripped text so a keyword hidden in a comment cannot trip us,
        // and a keyword hidden *behind* a comment cannot slip past us.
        String cleaned = stripTrailingSemicolon(stripped).trim();

        if (!STARTS_WITH_SELECT_OR_WITH.matcher(cleaned).find()) {
            throw new RejectedException("Only SELECT and WITH queries are allowed. This query is read-only access.");
        }

        String forbidden = findForbiddenWord(cleaned);
        if (forbidden != null) {
            throw new RejectedException(
                    "The keyword or function '" + forbidden + "' is not permitted. Only read-only SELECT queries are allowed.");
        }

        // Rebuild from the original text (minus comments) so the executed SQL still contains the
        // string literals verbatim — stripCommentsAndBlankLiterals only blanks them for scanning.
        String executable = stripTrailingSemicolon(stripComments(sql)).trim();

        if (!HAS_LIMIT.matcher(cleaned).find()) {
            executable = executable + " LIMIT " + maxRows;
        }
        return executable;
    }

    /**
     * Scan for forbidden identifiers. Only words outside string literals count, and a word
     * immediately preceded by '.' is a column/field reference (e.g. {@code t.set}), not a keyword.
     */
    private String findForbiddenWord(String cleanedSql) {
        String upper = cleanedSql.toUpperCase(Locale.ROOT);
        var matcher = WORD.matcher(upper);
        while (matcher.find()) {
            String word = matcher.group();
            int start = matcher.start();
            if (start > 0 && upper.charAt(start - 1) == '.') {
                continue; // qualified reference, not a statement keyword
            }
            if (FORBIDDEN_KEYWORDS.contains(word)) {
                return word;
            }
            if (FORBIDDEN_FUNCTIONS.contains(word) && isFollowedByOpenParen(upper, matcher.end())) {
                return word;
            }
        }
        return null;
    }

    private boolean isFollowedByOpenParen(String sql, int from) {
        for (int k = from; k < sql.length(); k++) {
            char ch = sql.charAt(k);
            if (ch == '(') {
                return true;
            }
            if (!Character.isWhitespace(ch)) {
                return false;
            }
        }
        return false;
    }

    /** True if a ';' appears outside a string literal and is followed by more than whitespace. */
    private boolean containsStatementSeparator(String blanked) {
        int idx = blanked.indexOf(';');
        while (idx >= 0) {
            if (!blanked.substring(idx + 1).isBlank()) {
                return true;
            }
            idx = blanked.indexOf(';', idx + 1);
        }
        return false;
    }

    private String stripTrailingSemicolon(String sql) {
        String trimmed = sql.stripTrailing();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * Remove comments, preserving string literals as-is. Used to build the SQL we actually run.
     */
    String stripComments(String sql) {
        return scan(sql, false);
    }

    /**
     * Remove comments <em>and</em> replace the contents of every string literal with spaces.
     * Used for scanning only: it makes keyword and separator detection immune to anything a
     * literal might contain, while preserving character offsets.
     */
    String stripCommentsAndBlankLiterals(String sql) {
        return scan(sql, true);
    }

    /**
     * Single-pass lexer over the SQL text, tracking which literal/comment context we are in.
     * Handles: single-quoted strings (with '' escaping), double-quoted identifiers (with ""
     * escaping), dollar-quoted strings ($tag$ ... $tag$), line comments (--) and block comments
     * (/* ... *&#47;), which DuckDB does not nest.
     */
    private String scan(String sql, boolean blankLiterals) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);

            // -- line comment
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
                continue;
            }

            // /* block comment */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
                continue;
            }

            // 'single-quoted string'
            if (c == '\'') {
                int start = i;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2; // escaped quote
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                appendLiteral(out, sql, start, i, blankLiterals);
                continue;
            }

            // "double-quoted identifier"
            if (c == '"') {
                int start = i;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '"') {
                        if (i + 1 < n && sql.charAt(i + 1) == '"') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                // Quoted identifiers are kept verbatim even when blanking: they name real columns,
                // and blanking them would hide a genuine reference from the scanner.
                out.append(sql, start, i);
                continue;
            }

            // $tag$ dollar-quoted string $tag$
            if (c == '$') {
                int tagEnd = sql.indexOf('$', i + 1);
                if (tagEnd > i && isValidDollarTag(sql, i + 1, tagEnd)) {
                    String delimiter = sql.substring(i, tagEnd + 1);
                    int close = sql.indexOf(delimiter, tagEnd + 1);
                    int start = i;
                    i = (close < 0) ? n : close + delimiter.length();
                    appendLiteral(out, sql, start, i, blankLiterals);
                    continue;
                }
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private void appendLiteral(StringBuilder out, String sql, int start, int end, boolean blank) {
        if (blank) {
            out.append(" ".repeat(end - start));
        } else {
            out.append(sql, start, end);
        }
    }

    private boolean isValidDollarTag(String sql, int from, int to) {
        for (int k = from; k < to; k++) {
            char ch = sql.charAt(k);
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                return false;
            }
        }
        return true;
    }
}
