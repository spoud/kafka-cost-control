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

    /**
     * Stands in for a blanked literal. Not whitespace, and not a word character, so the keyword and
     * LIMIT scans ignore it while {@link #validateTableTargets} can still see that a literal was
     * there.
     */
    private static final char LITERAL_MARK = '\u0001';

    /** Ends the table list of a FROM clause. */
    private static final Set<String> CLAUSE_ENDERS = Set.of(
            "SELECT", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET",
            "UNION", "INTERSECT", "EXCEPT", "ON", "USING", "WINDOW", "QUALIFY");

    /**
     * The only functions allowed to produce a table. These compute rows rather than reading them
     * from anywhere, so they cannot reach outside the database.
     */
    private static final Set<String> SAFE_TABLE_FUNCTIONS = Set.of("RANGE", "GENERATE_SERIES", "UNNEST");

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

        validateTableTargets(stripped);

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

    /**
     * Restricts what may stand where a table goes. DuckDB resolves a bare string there as a file
     * path (a "replacement scan"), so {@code FROM '/etc/hosts'} reads that file without naming any
     * function the keyword scan could catch. A table position may therefore only hold a subquery,
     * an identifier, or one of {@link #SAFE_TABLE_FUNCTIONS}.
     * <p>
     * Allow-listing this one position rather than denying known-bad functions is deliberate: the
     * set of functions that can reach a filesystem grows with every DuckDB release, but the set of
     * things that may legitimately name a table does not.
     *
     * @param marked SQL with comments removed and literals replaced by {@link #LITERAL_MARK}
     */
    private void validateTableTargets(String marked) {
        int i = 0;
        int n = marked.length();
        boolean inFromClause = false;
        boolean expectTarget = false;
        int depth = 0;

        while (i < n) {
            char c = marked.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(') {
                depth++;
                expectTarget = false; // a derived table: its own FROM is checked on the way through
                i++;
            } else if (c == ')') {
                depth--;
                i++;
            } else if (c == ',') {
                expectTarget = inFromClause && depth == 0;
                i++;
            } else if (c == LITERAL_MARK) {
                if (expectTarget) {
                    throw new RejectedException(
                            "A quoted string cannot name a table. Query the tables described in the schema instead.");
                }
                while (i < n && marked.charAt(i) == LITERAL_MARK) {
                    i++;
                }
            } else if (c == '"') {
                expectTarget = false; // quoted identifier, i.e. a real table name
                i++;
                while (i < n && marked.charAt(i) != '"') {
                    i++;
                }
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(marked.charAt(i)) || marked.charAt(i) == '_')) {
                    i++;
                }
                String word = marked.substring(start, i).toUpperCase(Locale.ROOT);

                if (expectTarget) {
                    if (isFollowedByOpenParen(marked, i) && !SAFE_TABLE_FUNCTIONS.contains(word)) {
                        throw new RejectedException("'" + word
                                + "' cannot be used to produce a table. Query the tables described in the schema instead.");
                    }
                    expectTarget = false;
                }

                if (word.equals("FROM") || word.equals("JOIN")) {
                    inFromClause = true;
                    expectTarget = true;
                } else if (CLAUSE_ENDERS.contains(word)) {
                    inFromClause = false;
                    expectTarget = false;
                }
            } else {
                i++;
            }
        }
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
            out.append(String.valueOf(LITERAL_MARK).repeat(end - start));
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
