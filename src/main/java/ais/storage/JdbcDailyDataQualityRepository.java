package ais.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcDailyDataQualityRepository {

    private final SqliteDatabase database;

    public JdbcDailyDataQualityRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public List<StoredDailyDataQuality> query(
            DailyDataQualityQuery query) {
        Objects.requireNonNull(query, "query");
        String sql = """
                WITH selected_runs AS (
                    SELECT id, target_date, input_name,
                           input_uncompressed_bytes, input_file_count,
                           accepted_interval_count,
                           estimated_missing_count,
                           metadata_update_count,
                           outside_distance_range_count
                      FROM analysis_run
                     WHERE source_mode = 'HISTORICAL'
                       AND active = 1
                       AND status = 'COMPLETE'
                       AND target_date >= ?
                       AND target_date <= ?
                       AND receiver_profile_id = ?
                       AND analysis_profile_id = ?
                ),
                diagnostic_rollup AS (
                    SELECT run.id AS analysis_run_id,
                           COALESCE(SUM(CASE
                               WHEN diagnostic.diagnostic_code LIKE
                                    'INTERVAL_%'
                               THEN diagnostic.occurrence_count ELSE 0 END), 0)
                               AS interval_event_count,
                           COALESCE(SUM(CASE
                               WHEN diagnostic.diagnostic_code = 'DUPLICATE'
                               THEN diagnostic.occurrence_count ELSE 0 END), 0)
                               AS duplicate_count,
                           COALESCE(SUM(CASE
                               WHEN diagnostic.diagnostic_code IN (
                                   'INVALID_LOG_LINE',
                                   'INVALID_NMEA_FORMAT',
                                   'CHECKSUM_MISSING',
                                   'CHECKSUM_INVALID',
                                   'INVALID_FRAGMENT',
                                   'INCOMPLETE_FRAGMENT',
                                   'DECODE_FAILED',
                                   'INVALID_MMSI',
                                   'OUTSIDE_SELECTED_DAY')
                               THEN diagnostic.occurrence_count ELSE 0 END), 0)
                               AS input_anomaly_count,
                           COALESCE(SUM(CASE
                               WHEN diagnostic.diagnostic_code IN (
                                   'INVALID_POSITION',
                                   'INTERVAL_INVALID_POSITION')
                               THEN diagnostic.occurrence_count ELSE 0 END), 0)
                               AS invalid_position_count,
                           COALESCE(SUM(CASE
                               WHEN diagnostic.diagnostic_code =
                                    'INTERVAL_GAP_30_MINUTES_OR_MORE'
                               THEN diagnostic.occurrence_count ELSE 0 END), 0)
                               AS thirty_minute_gap_count,
                           COALESCE(SUM(CASE
                               WHEN diagnostic.diagnostic_code =
                                    'INTERVAL_DISTANCE_JUMP_OVER_30_KM'
                               THEN diagnostic.occurrence_count ELSE 0 END), 0)
                               AS distance_jump_count,
                           COALESCE(SUM(CASE
                               WHEN diagnostic.diagnostic_code IN (
                                   'SOURCE_OVERFLOW', 'SOURCE_READ_FAILED')
                               THEN diagnostic.occurrence_count ELSE 0 END), 0)
                               AS source_failure_count
                      FROM selected_runs run
                      LEFT JOIN analysis_diagnostic_summary diagnostic
                        ON diagnostic.analysis_run_id = run.id
                     GROUP BY run.id
                ),
                bucket_rollup AS (
                    SELECT metric.analysis_run_id,
                           MIN(metric.bucket_start) AS first_bucket,
                           MAX(metric.bucket_start) AS last_bucket,
                           COUNT(DISTINCT metric.bucket_start)
                               AS bucket_count
                      FROM distance_metric_5m metric
                      JOIN selected_runs run
                        ON run.id = metric.analysis_run_id
                     GROUP BY metric.analysis_run_id
                ),
                vessel_rollup AS (
                    SELECT presence.analysis_run_id,
                           COUNT(DISTINCT presence.mmsi)
                               AS vessel_count
                      FROM distance_vessel_presence_day presence
                      JOIN selected_runs run
                        ON run.id = presence.analysis_run_id
                     GROUP BY presence.analysis_run_id
                )
                SELECT run.target_date, run.input_name,
                       run.input_file_count,
                       run.input_uncompressed_bytes,
                       bucket.first_bucket, bucket.last_bucket,
                       COALESCE(bucket.bucket_count, 0) AS bucket_count,
                       COALESCE(vessel.vessel_count, 0) AS vessel_count,
                       run.accepted_interval_count,
                       run.estimated_missing_count,
                       run.metadata_update_count,
                       diagnostic.interval_event_count,
                       diagnostic.duplicate_count,
                       diagnostic.input_anomaly_count,
                       diagnostic.invalid_position_count,
                       diagnostic.thirty_minute_gap_count,
                       diagnostic.distance_jump_count,
                       diagnostic.source_failure_count,
                       run.outside_distance_range_count
                  FROM selected_runs run
                  JOIN diagnostic_rollup diagnostic
                    ON diagnostic.analysis_run_id = run.id
                  LEFT JOIN bucket_rollup bucket
                    ON bucket.analysis_run_id = run.id
                  LEFT JOIN vessel_rollup vessel
                    ON vessel.analysis_run_id = run.id
                 ORDER BY run.target_date
                """;
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                        sql)) {
            statement.setString(1, query.startDate().toString());
            statement.setString(2, query.endDate().toString());
            statement.setString(3, query.receiverProfileId().value());
            statement.setString(4, query.analysisProfileId().value());
            try (ResultSet results = statement.executeQuery()) {
                List<StoredDailyDataQuality> rows = new ArrayList<>();
                while (results.next()) {
                    rows.add(new StoredDailyDataQuality(
                            LocalDate.parse(results.getString("target_date")),
                            results.getString("input_name"),
                            results.getInt("input_file_count"),
                            results.getLong("input_uncompressed_bytes"),
                            nullableInstant(results, "first_bucket"),
                            nullableInstant(results, "last_bucket"),
                            results.getInt("bucket_count"),
                            results.getInt("vessel_count"),
                            results.getLong("accepted_interval_count"),
                            results.getLong("estimated_missing_count"),
                            results.getLong("metadata_update_count"),
                            results.getLong("interval_event_count"),
                            results.getLong("duplicate_count"),
                            results.getLong("input_anomaly_count"),
                            results.getLong("invalid_position_count"),
                            results.getLong("thirty_minute_gap_count"),
                            results.getLong("distance_jump_count"),
                            results.getLong("source_failure_count"),
                            results.getLong("outside_distance_range_count")));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not read daily data quality", failure);
        }
    }

    private static Instant nullableInstant(ResultSet results, String column)
            throws SQLException {
        String value = results.getString(column);
        return value == null ? null : Instant.parse(value);
    }
}
