package io.spoud.kcc.aggregator.ai;

import io.quarkus.logging.Log;
import io.spoud.kcc.aggregator.olap.OlapInfra;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes model-authored SQL under containment.
 * <p>
 * Four independent layers protect the database; this class owns three of them, and
 * {@link SqlGuard} owns the fourth (statement validation). No single layer is trusted:
 * <ol>
 *   <li><b>Isolated connection</b> — a duplicate of the shared DuckDB connection, so a long
 *       analytical query does not contend with the synchronized ingest flush.</li>
 *   <li><b>Statement validation</b> ({@link SqlGuard}) — the exfiltration guard. Rejects
 *       {@code COPY}, {@code ATTACH}, {@code INSTALL}/{@code LOAD} and the {@code read_*} table
 *       functions before anything reaches the engine.
 *       <p>
 *       An earlier version also set {@code enable_external_access=false} on this connection as
 *       defence in depth. That was removed: in DuckDB the setting is <em>global to the database
 *       instance</em>, not per-connection, and one-way. Setting it here disabled
 *       {@code COPY ... TO} on the shared connection too, permanently breaking
 *       {@code /olap/export} for the lifetime of the process after the first assistant query.</li>
 *   <li><b>Unconditional rollback</b> — every query runs inside an explicit transaction that is
 *       always rolled back. DuckDB DDL is transactional, so this reverts anything that somehow
 *       got past layers 1-2 and {@link SqlGuard}.</li>
 * </ol>
 * Plus resource caps: a row limit and a wall-clock timeout enforced by cancelling the statement.
 */
@ApplicationScoped
public class ReadOnlyQueryExecutor {

    private final OlapInfra olapInfra;
    private final SqlGuard sqlGuard;
    private final AiConfigProperties aiConfig;
    private final ExecutorService queryExecutor;

    public ReadOnlyQueryExecutor(OlapInfra olapInfra, SqlGuard sqlGuard, AiConfigProperties aiConfig) {
        this.olapInfra = olapInfra;
        this.sqlGuard = sqlGuard;
        this.aiConfig = aiConfig;
        this.queryExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ai-sql-query");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        queryExecutor.shutdownNow();
    }

    /** Tabular result of a successful query. */
    public record QueryResult(List<String> columns, List<List<String>> rows, String executedSql, boolean truncated) {
        public int rowCount() {
            return rows.size();
        }
    }

    /** Raised when a query cannot be run. The message is fed back to the model so it can retry. */
    public static class QueryFailedException extends RuntimeException {
        public QueryFailedException(String message) {
            super(message);
        }
    }

    /**
     * Validate and execute a read-only query.
     *
     * @param sql model-authored SQL
     * @return the result set, rendered as strings
     * @throws SqlGuard.RejectedException if validation fails
     * @throws QueryFailedException       if OLAP is unavailable, the query times out, or it errors
     */
    public QueryResult execute(String sql) {
        int maxRows = aiConfig.maxRows();
        String safeSql = sqlGuard.validate(sql, maxRows);

        Connection conn = olapInfra.duplicateConnection()
                .orElseThrow(() -> new QueryFailedException(
                        "The analytics database is not available. It is likely disabled (cc.olap.enabled=false)."));

        try (conn) {
            harden(conn);
            return runWithTimeout(conn, safeSql, maxRows);
        } catch (SQLException e) {
            Log.debugf(e, "Model-authored query failed: %s", safeSql);
            throw new QueryFailedException("Query failed: " + rootMessage(e));
        }
    }

    /**
     * Put this connection in an explicit transaction so everything it does can be rolled back.
     * <p>
     * Deliberately does <em>not</em> touch {@code enable_external_access}: that setting is global
     * to the DuckDB instance rather than per-connection, so disabling it here would also disable
     * {@code COPY ... TO} on the shared connection and break {@code /olap/export}.
     * Filesystem and network access are blocked by {@link SqlGuard} instead.
     */
    private void harden(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
    }

    private QueryResult runWithTimeout(Connection conn, String safeSql, int maxRows) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Fetch one row beyond the cap so truncation is *detectable*. With setMaxRows(maxRows)
            // the driver silently stops at the limit and the result looks complete, which would
            // let the model report a partial total as if it were the whole answer.
            stmt.setMaxRows(maxRows + 1);

            Future<QueryResult> future = queryExecutor.submit(() -> {
                try (ResultSet rs = stmt.executeQuery(safeSql)) {
                    return materialise(rs, safeSql, maxRows);
                }
            });

            try {
                return future.get(aiConfig.queryTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                cancelQuietly(stmt);
                throw new QueryFailedException(
                        "Query exceeded the " + aiConfig.queryTimeout().toSeconds()
                                + "s time limit. Narrow the time range or aggregate more aggressively.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QueryFailedException("Query was interrupted.");
            } catch (java.util.concurrent.ExecutionException e) {
                throw new QueryFailedException("Query failed: " + rootMessage(e.getCause()));
            }
        } finally {
            // Always roll back — the query should never have written anything, and if it somehow
            // did, this undoes it. DuckDB DDL is transactional too.
            try {
                conn.rollback();
            } catch (SQLException e) {
                Log.warn("Failed to roll back read-only query transaction", e);
            }
        }
    }

    private QueryResult materialise(ResultSet rs, String executedSql, int maxRows) throws SQLException {
        var meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        var columns = new ArrayList<String>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        var rows = new ArrayList<List<String>>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            var row = new ArrayList<String>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                row.add(value == null ? null : String.valueOf(value));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, executedSql, truncated);
    }

    private void cancelQuietly(Statement stmt) {
        try {
            stmt.cancel();
        } catch (SQLException e) {
            Log.debug("Failed to cancel timed-out query", e);
        }
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }
}
