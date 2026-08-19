package io.spoud.kcc.aggregator.ai;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates model-authored SQL: accepts a single read-only query, rejects everything else.
 * <p>
 * Parsing is literal- and comment-aware. A raw regex cannot tell
 * {@code WHERE name = 'a;DROP TABLE x'} (legitimate) from
 * {@code SELECT 1; DROP TABLE aggregated_data} (not).
 */
@ApplicationScoped
public class SqlGuard {

    /** Rejected anywhere, including in a subquery or CTE. */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "UPSERT",
            // REPLACE is absent on purpose: it is a string function. CREATE covers CREATE OR REPLACE.
            "CREATE", "DROP", "ALTER",
            "COPY", "EXPORT", "IMPORT", "ATTACH", "DETACH", "INSTALL", "LOAD",
            "PRAGMA", "SET", "RESET", "CALL", "CHECKPOINT", "VACUUM", "ANALYZE",
            "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "PREPARE", "EXECUTE", "DEALLOCATE");

    /**
     * Table functions that reach outside the database file. Matched only in function position, so
     * {@code GLOB} as an operator ({@code name GLOB '*.log'}) still works.
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
     * @return the query normalised: comments stripped, trailing semicolon removed, {@code LIMIT}
     *         appended if absent
     * @throws RejectedException if it is anything but a single read-only query
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

        String cleaned = stripTrailingSemicolon(stripped).trim();

        if (!STARTS_WITH_SELECT_OR_WITH.matcher(cleaned).find()) {
            throw new RejectedException("Only SELECT and WITH queries are allowed. This query is read-only access.");
        }

        String forbidden = findForbiddenWord(cleaned);
        if (forbidden != null) {
            throw new RejectedException(
                    "The keyword or function '" + forbidden + "' is not permitted. Only read-only SELECT queries are allowed.");
        }

        // From the original text: literals are blanked for scanning only, not for execution.
        String executable = stripTrailingSemicolon(stripComments(sql)).trim();

        if (!HAS_LIMIT.matcher(cleaned).find()) {
            executable = executable + " LIMIT " + maxRows;
        }
        return executable;
    }

    /** A word preceded by '.' is a qualified reference ({@code t.set}), not a keyword. */
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

    /** Remove comments, preserving literals. Produces the SQL that is executed. */
    String stripComments(String sql) {
        return scan(sql, false);
    }

    /**
     * Remove comments and blank out literal contents, preserving offsets. For scanning only: a
     * keyword or ';' inside a literal must not be detected.
     */
    String stripCommentsAndBlankLiterals(String sql) {
        return scan(sql, true);
    }

    /**
     * Single-pass lexer handling single-quoted strings ('' escapes), quoted identifiers,
     * dollar-quoted strings, and line/block comments.
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
                // Kept verbatim even when blanking: they name real columns.
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
