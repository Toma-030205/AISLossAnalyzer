package ais.storage;

import ais.domain.AnalysisRunId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcDiagnosticRepository
        implements DiagnosticRepository {

    private final SqliteDatabase database;
    private final TransactionRunner transactions;

    public JdbcDiagnosticRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transactions = new TransactionRunner(database);
    }

    @Override
    public void saveSummary(AnalysisRunId runId,
                            List<DiagnosticSummary> summaries) {
        Objects.requireNonNull(runId, "runId");
        List<DiagnosticSummary> copy = List.copyOf(summaries);
        transactions.execute(connection -> {
            replaceSummaries(connection, runId, copy);
            return null;
        });
    }

    @Override
    public void saveExclusionPeriods(AnalysisRunId runId,
                                     List<AnalysisExclusionPeriod> periods) {
        Objects.requireNonNull(runId, "runId");
        List<AnalysisExclusionPeriod> copy = List.copyOf(periods);
        transactions.execute(connection -> {
            replaceExclusionPeriods(connection, runId, copy);
            return null;
        });
    }

    @Override
    public List<DiagnosticSummary> findSummaries(AnalysisRunId runId) {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT diagnostic_code, occurrence_count,
                               first_occurred_at, last_occurred_at
                          FROM analysis_diagnostic_summary
                         WHERE analysis_run_id = ?
                         ORDER BY diagnostic_code
                        """)) {
            statement.setString(1, runId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<DiagnosticSummary> summaries = new ArrayList<>();
                while (results.next()) {
                    summaries.add(new DiagnosticSummary(
                            results.getString("diagnostic_code"),
                            results.getLong("occurrence_count"),
                            JdbcSupport.nullableInstant(results,
                                    "first_occurred_at"),
                            JdbcSupport.nullableInstant(results,
                                    "last_occurred_at")));
                }
                return List.copyOf(summaries);
            }
        } catch (SQLException failure) {
            throw new StorageException("could not read diagnostics", failure);
        }
    }

    @Override
    public List<AnalysisExclusionPeriod> findExclusionPeriods(
            AnalysisRunId runId) {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT started_at, ended_at, reason, affected_count
                          FROM analysis_exclusion_period
                         WHERE analysis_run_id = ?
                         ORDER BY started_at, id
                        """)) {
            statement.setString(1, runId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<AnalysisExclusionPeriod> periods = new ArrayList<>();
                while (results.next()) {
                    periods.add(new AnalysisExclusionPeriod(
                            Instant.parse(results.getString("started_at")),
                            Instant.parse(results.getString("ended_at")),
                            results.getString("reason"),
                            results.getLong("affected_count")));
                }
                return List.copyOf(periods);
            }
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not read exclusion periods", failure);
        }
    }

    static void replaceSummaries(Connection connection, AnalysisRunId runId,
                                 List<DiagnosticSummary> summaries)
            throws SQLException {
        delete(connection, "analysis_diagnostic_summary", runId);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO analysis_diagnostic_summary (
                    analysis_run_id, diagnostic_code, occurrence_count,
                    first_occurred_at, last_occurred_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            for (DiagnosticSummary summary : summaries) {
                statement.setString(1, runId.toString());
                statement.setString(2, summary.code());
                statement.setLong(3, summary.count());
                JdbcSupport.setNullableString(statement, 4,
                        summary.firstOccurredAt());
                JdbcSupport.setNullableString(statement, 5,
                        summary.lastOccurredAt());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    static void replaceExclusionPeriods(Connection connection,
                                        AnalysisRunId runId,
                                        List<AnalysisExclusionPeriod> periods)
            throws SQLException {
        delete(connection, "analysis_exclusion_period", runId);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO analysis_exclusion_period (
                    analysis_run_id, started_at, ended_at, reason,
                    affected_count)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            for (AnalysisExclusionPeriod period : periods) {
                statement.setString(1, runId.toString());
                statement.setString(2,
                        JdbcSupport.instant(period.startedAt()));
                statement.setString(3,
                        JdbcSupport.instant(period.endedAt()));
                statement.setString(4, period.reason());
                statement.setLong(5, period.affectedCount());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void delete(Connection connection, String table,
                               AnalysisRunId runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE analysis_run_id = ?")) {
            statement.setString(1, runId.toString());
            statement.executeUpdate();
        }
    }
}
