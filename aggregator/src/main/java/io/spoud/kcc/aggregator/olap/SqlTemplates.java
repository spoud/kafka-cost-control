package io.spoud.kcc.aggregator.olap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

@ApplicationScoped
public class SqlTemplates {
    @Inject
    Template initDb;

    @Inject
    Template getMetricNames;

    @Inject
    Template getAllJsonKeys;

    @Inject
    Template getAllJsonKeyValues;

    @Inject
    Template exportData;

    @Inject
    Template importData;

    @Inject
    Template getHistory;

    @Inject
    Template getHistoryGrouped;

    @Inject
    Template insertValues;

    public PreparedStatement initDb(Connection conn) throws SQLException {
        return conn.prepareStatement(initDb.render());
    }

    public PreparedStatement getMetricNames(Connection conn) throws SQLException {
        return conn.prepareStatement(getMetricNames.render());
    }

    public PreparedStatement getAllJsonKeys(Connection conn, String column) throws SQLException {
        TemplateInstance template = getAllJsonKeys.data("column", column);
        return conn.prepareStatement(template.render());
    }

    public PreparedStatement getAllJsonKeyValues(Connection conn, String column, String key) throws SQLException {
        TemplateInstance template = getAllJsonKeyValues.data("column", column, "key", key);
        return conn.prepareStatement(template.render());
    }

    public PreparedStatement exportData(Connection conn,
                                        Path tmpFileName,
                                        String finalFormat,
                                        Instant from, Instant to) throws SQLException {
        TemplateInstance template = exportData.data("tmpFileName", tmpFileName, "finalFormat", finalFormat);
        var stmt = conn.prepareStatement(template.render());
        stmt.setObject(1, from.atOffset(ZoneOffset.UTC));
        stmt.setObject(2, to.atOffset(ZoneOffset.UTC));
        return stmt;
    }

    public PreparedStatement importData(Connection conn, Path filename) throws SQLException {
        return conn.prepareStatement(importData.data("path", filename).render());
    }

    public PreparedStatement getHistory(Connection conn, Set<String> metricNames,
                                        Instant from, Instant to) throws SQLException {
        TemplateInstance template = getHistory.data("metricNames", metricNames);
        var stmt = conn.prepareStatement(template.render());
        stmt.setObject(1, from.atOffset(ZoneOffset.UTC));
        stmt.setObject(2, to.atOffset(ZoneOffset.UTC));
        var i = 3;
        for (var name : metricNames) {
            stmt.setString(i++, name);
        }
        return stmt;
    }

    public PreparedStatement getHistoryGrouped(Connection conn, boolean groupByHour, Set<String> metricNames,
                                               Instant from, Instant to, String groupByContextKey) throws SQLException {
        var template = getHistoryGrouped.data("groupByHour", groupByHour, "metricNames", metricNames);
        var stmt = conn.prepareStatement(template.render());
        stmt.setObject(1, from.atOffset(ZoneOffset.UTC));
        stmt.setObject(2, to.atOffset(ZoneOffset.UTC));
        var i = 3;
        for (var name : metricNames) {
            stmt.setString(i++, name);
        }
        stmt.setString(i, groupByContextKey);
        return stmt;
    }

    public PreparedStatement insertValues(Connection conn) throws SQLException {
        String sql = insertValues.render();
        var stmt = conn.prepareStatement(sql);
        return stmt;
    }
}
