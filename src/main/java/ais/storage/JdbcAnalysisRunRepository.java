package ais.storage;

import ais.analysis.AnalysisRunSummary;
import ais.domain.AnalysisProfileId;
import ais.domain.AnalysisRunId;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.input.history.InputFingerprint;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcAnalysisRunRepository
        implements AnalysisRunRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, source_mode, target_date, input_name, input_sha256,
                   input_uncompressed_bytes, input_file_count,
                   receiver_profile_id, analysis_profile_id, started_at,
                   ended_at, status, active, accepted_interval_count,
                   estimated_missing_count, metadata_update_count,
                   outside_distance_range_count
            FROM analysis_run
            """;

    private final SqliteDatabase database;
    private final TransactionRunner transactions;

    public JdbcAnalysisRunRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transactions = new TransactionRunner(database);
    }

    @Override
    public AnalysisRunId begin(AnalysisRun run) {
        Objects.requireNonNull(run, "run");
        transactions.execute(connection -> {
            insertPending(connection, run);
            return null;
        });
        return run.id();
    }

    @Override
    public void complete(AnalysisRunSummary summary) {
        Objects.requireNonNull(summary, "summary");
        transactions.execute(connection -> {
            publishCompleted(connection, summary);
            return null;
        });
    }

    @Override
    public Optional<StoredAnalysisRun> findEquivalent(
            InputFingerprint input,
            ReceiverProfileId receiver,
            AnalysisProfileId profile) {
        return findEquivalent(null, input, receiver, profile);
    }

    @Override
    public Optional<StoredAnalysisRun> findEquivalent(
            LocalDate targetDate,
            InputFingerprint input,
            ReceiverProfileId receiver,
            AnalysisProfileId profile) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(profile, "profile");
        String sql = SELECT_COLUMNS + """
             WHERE source_mode = 'HISTORICAL'
               AND input_sha256 = ?
               AND input_uncompressed_bytes = ?
               AND input_file_count = ?
               AND receiver_profile_id = ?
               AND analysis_profile_id = ?
               AND active = 1 AND status = 'COMPLETE'
            """ + (targetDate == null ? "" : " AND target_date = ?") + """
             ORDER BY ended_at DESC
             LIMIT 1
            """;
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, input.sha256());
            statement.setLong(2, input.uncompressedBytes());
            statement.setInt(3, input.fileCount());
            statement.setString(4, receiver.value());
            statement.setString(5, profile.value());
            if (targetDate != null) {
                statement.setString(6, targetDate.toString());
            }
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(map(results))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("could not find equivalent analysis",
                    failure);
        }
    }

    @Override
    public Optional<StoredAnalysisRun> findById(AnalysisRunId runId) {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                        SELECT_COLUMNS + " WHERE id = ?")) {
            statement.setString(1, runId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(map(results))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("could not read analysis run", failure);
        }
    }

    @Override
    public List<StoredAnalysisRun> findCompleted(
            Instant fromInclusive,
            Instant toExclusive,
            ReceiverProfileId receiver,
            AnalysisProfileId profile) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(profile, "profile");
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append("""
             WHERE active = 1 AND status = 'COMPLETE'
               AND receiver_profile_id = ?
               AND analysis_profile_id = ?
            """);
        if (fromInclusive != null) {
            sql.append(" AND COALESCE(ended_at, started_at) >= ?");
        }
        if (toExclusive != null) {
            sql.append(" AND started_at < ?");
        }
        sql.append(" ORDER BY started_at, id");
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                        sql.toString())) {
            int index = 1;
            statement.setString(index++, receiver.value());
            statement.setString(index++, profile.value());
            if (fromInclusive != null) {
                statement.setString(index++, JdbcSupport.instant(fromInclusive));
            }
            if (toExclusive != null) {
                statement.setString(index, JdbcSupport.instant(toExclusive));
            }
            try (ResultSet results = statement.executeQuery()) {
                List<StoredAnalysisRun> runs = new ArrayList<>();
                while (results.next()) {
                    runs.add(map(results));
                }
                return List.copyOf(runs);
            }
        } catch (SQLException failure) {
            throw new StorageException("could not list completed analyses",
                    failure);
        }
    }

    static void insertPending(Connection connection, AnalysisRun run)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO analysis_run (
                    id, source_mode, target_date, input_name, input_sha256,
                    input_uncompressed_bytes, input_file_count,
                    receiver_profile_id, analysis_profile_id, started_at,
                    status, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)
                """)) {
            statement.setString(1, run.id().toString());
            statement.setString(2, run.sourceMode().name());
            JdbcSupport.setNullableString(statement, 3, run.targetDate());
            statement.setString(4, run.inputName());
            InputFingerprint fingerprint = run.inputFingerprint();
            statement.setString(5,
                    fingerprint == null ? null : fingerprint.sha256());
            if (fingerprint == null) {
                statement.setObject(6, null);
                statement.setObject(7, null);
            } else {
                statement.setLong(6, fingerprint.uncompressedBytes());
                statement.setInt(7, fingerprint.fileCount());
            }
            statement.setString(8, run.receiverProfileId().value());
            statement.setString(9, run.analysisProfileId().value());
            statement.setString(10, JdbcSupport.instant(run.startedAt()));
            statement.setString(11, JdbcSupport.instant(Instant.now()));
            statement.executeUpdate();
        }
    }

    static void publishCompleted(Connection connection,
                                 AnalysisRunSummary summary)
            throws SQLException {
        String runId = summary.context().runId().toString();
        try (PreparedStatement supersede = connection.prepareStatement("""
                UPDATE analysis_run AS old
                   SET active = 0, status = 'SUPERSEDED'
                 WHERE old.id <> ?
                   AND old.source_mode = 'HISTORICAL'
                   AND old.active = 1
                   AND old.status = 'COMPLETE'
                   AND EXISTS (
                       SELECT 1 FROM analysis_run AS fresh
                        WHERE fresh.id = ?
                          AND fresh.source_mode = 'HISTORICAL'
                          AND fresh.target_date = old.target_date
                          AND fresh.input_sha256 = old.input_sha256
                          AND fresh.input_uncompressed_bytes = old.input_uncompressed_bytes
                          AND fresh.input_file_count = old.input_file_count
                          AND fresh.receiver_profile_id = old.receiver_profile_id
                          AND fresh.analysis_profile_id = old.analysis_profile_id)
                """)) {
            supersede.setString(1, runId);
            supersede.setString(2, runId);
            supersede.executeUpdate();
        }

        try (PreparedStatement complete = connection.prepareStatement("""
                UPDATE analysis_run
                   SET ended_at = ?, status = 'COMPLETE', active = 1,
                       accepted_interval_count = ?,
                       estimated_missing_count = ?,
                       metadata_update_count = ?,
                       outside_distance_range_count = ?
                 WHERE id = ? AND status = 'PENDING'
                """)) {
            complete.setString(1, JdbcSupport.instant(summary.endedAt()));
            complete.setLong(2, summary.acceptedIntervalCount());
            complete.setLong(3, summary.estimatedMissingCount());
            complete.setLong(4, summary.metadataUpdateCount());
            complete.setLong(5,
                    summary.aggregation().outsideDistanceRangeCount());
            complete.setString(6, runId);
            if (complete.executeUpdate() != 1) {
                throw new SQLException(
                        "analysis run is missing or is no longer pending: "
                                + runId);
            }
        }
    }

    static void reopenLive(Connection connection, AnalysisRunId runId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE analysis_run
                   SET ended_at = NULL, status = 'PENDING', active = 0
                 WHERE id = ? AND source_mode = 'LIVE'
                   AND status = 'COMPLETE'
                """)) {
            statement.setString(1, runId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "completed live run could not be reopened: " + runId);
            }
        }
    }

    private static StoredAnalysisRun map(ResultSet results)
            throws SQLException {
        SourceMode sourceMode = SourceMode.valueOf(
                results.getString("source_mode"));
        String sha256 = results.getString("input_sha256");
        InputFingerprint fingerprint = sha256 == null ? null
                : new InputFingerprint(
                        sha256,
                        results.getLong("input_uncompressed_bytes"),
                        results.getInt("input_file_count"));
        AnalysisRun run = new AnalysisRun(
                AnalysisRunId.parse(results.getString("id")),
                sourceMode,
                JdbcSupport.nullableDate(results, "target_date"),
                results.getString("input_name"),
                fingerprint,
                new ReceiverProfileId(
                        results.getString("receiver_profile_id")),
                new AnalysisProfileId(
                        results.getString("analysis_profile_id")),
                Instant.parse(results.getString("started_at")));
        return new StoredAnalysisRun(
                run,
                AnalysisRunStatus.valueOf(results.getString("status")),
                results.getInt("active") == 1,
                JdbcSupport.nullableInstant(results, "ended_at"),
                results.getLong("accepted_interval_count"),
                results.getLong("estimated_missing_count"),
                results.getLong("metadata_update_count"),
                results.getLong("outside_distance_range_count"));
    }
}
