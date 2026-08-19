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
 * Executes model-authored SQL under containment:
 * <ol>
 *   <li>a connection duplicated from the shared one, so long queries do not contend with ingest;</li>
 *   <li>{@link SqlGuard} validation, which also blocks filesystem and network access;</li>
 *   <li>an unconditional rollback — DuckDB DDL is transactional, so this reverts anything that
 *       got past the guard;</li>
 *   <li>a row cap and a wall-clock timeout.</li>
 * </ol>
 * Note {@code enable_external_access} is not used: it is global to the DuckDB instance, so
 * disabling it here would break {@code COPY ... TO} on the shared connection and with it
 * {@code /olap/export}.
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
     * @throws SqlGuard.RejectedException if validation fails
     * @throws QueryFailedException       if OLAP is unavailable, or the query times out or errors
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

    /** Explicit transaction, so everything this connection does can be rolled back. */
    private void harden(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
    }

    private QueryResult runWithTimeout(Connection conn, String safeSql, int maxRows) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // One row beyond the cap, so truncation is detectable rather than looking complete.
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
