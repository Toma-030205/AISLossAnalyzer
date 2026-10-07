package ais.storage;

import ais.aggregate.MetricCounts;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Calculates loss and freshness numerators/denominators by ship length,
 * distance band and AIS class from the compact daily per-vessel facts.
 */
public final class JdbcShipLengthPerformanceRepository {

    private final SqliteDatabase database;

    public JdbcShipLengthPerformanceRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public StoredShipLengthPerformance query(
            ShipLengthPerformanceQuery query) {
        Objects.requireNonNull(query, "query");
        List<VesselClass> classes = query.vesselClasses().stream()
                .sorted()
                .toList();
        String placeholders = String.join(", ",
                Collections.nCopies(classes.size(), "?"));
        String metricExcluded = exclusionClause(
                "metric.observed_date", query.excludedDates().size());
        String runExcluded = exclusionClause(
                "target_date", query.excludedDates().size());
        String sql = """
                WITH selected_metrics AS (
                    SELECT metric.analysis_run_id,
                           metric.observed_date,
                           metric.distance_band_index,
                           metric.lower_kilometers,
                           metric.upper_kilometers,
                           metric.vessel_class,
                           metric.mmsi,
                           metric.observed_count,
                           metric.missing_count,
                           metric.observed_seconds,
                           metric.stale_seconds
                      FROM distance_vessel_metric_day metric
                      JOIN analysis_run run
                        ON run.id = metric.analysis_run_id
                     WHERE run.active = 1
                       AND run.status = 'COMPLETE'
                       AND run.vessel_metric_ready = 1
                       AND run.receiver_profile_id = ?
                       AND run.analysis_profile_id = ?
                       AND metric.observed_date >= ?
                       AND metric.observed_date <= ?
                       $METRIC_EXCLUDED$
                       AND metric.vessel_class IN ($CLASSES$)
                ),
                length_candidates AS (
                    SELECT metric.*,
                           history.ship_length_meters,
                           ROW_NUMBER() OVER (
                               PARTITION BY metric.analysis_run_id,
                                            metric.observed_date,
                                            metric.distance_band_index,
                                            metric.vessel_class,
                                            metric.mmsi
                               ORDER BY history.valid_from DESC
                           ) AS length_row_number
                      FROM selected_metrics metric
                      LEFT JOIN vessel_metadata_history history
                        ON history.mmsi = metric.mmsi
                       AND history.vessel_class = metric.vessel_class
                       AND history.ship_length_meters IS NOT NULL
                       AND julianday(history.valid_from) < julianday(
                           metric.observed_date
                               || 'T00:00:00+09:00', '+1 day')
                ),
                classified AS (
                    SELECT candidate.*,
                           CASE
                               WHEN candidate.ship_length_meters IS NULL
                                 OR candidate.ship_length_meters < 1
                                 OR candidate.ship_length_meters > 500 THEN 6
                               WHEN candidate.ship_length_meters < 50 THEN 0
                               WHEN candidate.ship_length_meters < 100 THEN 1
                               WHEN candidate.ship_length_meters < 150 THEN 2
                               WHEN candidate.ship_length_meters < 200 THEN 3
                               WHEN candidate.ship_length_meters < 250 THEN 4
                               ELSE 5
                           END AS ship_length_band_order
                      FROM length_candidates candidate
                     WHERE candidate.length_row_number = 1
                ),
                totals AS (
                    SELECT ship_length_band_order,
                           distance_band_index,
                           MIN(lower_kilometers) AS lower_kilometers,
                           MAX(upper_kilometers) AS upper_kilometers,
                           vessel_class,
                           SUM(observed_count) AS observed_count,
                           SUM(missing_count) AS missing_count,
                           SUM(observed_seconds) AS observed_seconds,
                           SUM(stale_seconds) AS stale_seconds,
                           COUNT(DISTINCT mmsi) AS distinct_vessel_count,
                           COUNT(DISTINCT observed_date)
                               AS observation_day_count
                      FROM classified
                     GROUP BY ship_length_band_order,
                              distance_band_index, vessel_class
                ),
                coverage AS (
                    SELECT COUNT(DISTINCT vessel_class || ':' || mmsi)
                               AS total_distinct_vessel_count,
                           COUNT(DISTINCT CASE
                               WHEN ship_length_band_order <> 6
                               THEN vessel_class || ':' || mmsi
                           END) AS known_length_distinct_vessel_count
                      FROM classified
                ),
                run_coverage AS (
                    SELECT SUM(CASE WHEN vessel_metric_ready = 1
                                    THEN 1 ELSE 0 END)
                               AS ready_analysis_run_count,
                           SUM(CASE WHEN vessel_metric_ready = 0
                                    THEN 1 ELSE 0 END)
                               AS reanalysis_required_run_count
                      FROM analysis_run
                     WHERE active = 1
                       AND status = 'COMPLETE'
                       AND source_mode = 'HISTORICAL'
                       AND receiver_profile_id = ?
                       AND analysis_profile_id = ?
                       AND target_date >= ?
                       AND target_date <= ?
                       $RUN_EXCLUDED$
                )
                SELECT COALESCE(run_coverage.ready_analysis_run_count, 0)
                           AS ready_analysis_run_count,
                       COALESCE(run_coverage.reanalysis_required_run_count, 0)
                           AS reanalysis_required_run_count,
                       coverage.total_distinct_vessel_count,
                       coverage.known_length_distinct_vessel_count,
                       totals.ship_length_band_order,
                       totals.distance_band_index,
                       totals.lower_kilometers,
                       totals.upper_kilometers,
                       totals.vessel_class,
                       totals.observed_count,
                       totals.missing_count,
                       totals.observed_seconds,
                       totals.stale_seconds,
                       totals.distinct_vessel_count,
                       totals.observation_day_count
                  FROM coverage
                  CROSS JOIN run_coverage
                  LEFT JOIN totals ON 1 = 1
                 ORDER BY totals.ship_length_band_order,
                          totals.distance_band_index,
                          totals.vessel_class
                """.replace("$METRIC_EXCLUDED$", metricExcluded)
                .replace("$RUN_EXCLUDED$", runExcluded)
                .replace("$CLASSES$", placeholders);
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                        sql)) {
            bind(statement, query, classes);
            return read(statement);
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not read ship-length performance analysis",
                    failure);
        }
    }

    private static void bind(
            PreparedStatement statement,
            ShipLengthPerformanceQuery query,
            List<VesselClass> classes) throws SQLException {
        int index = 1;
        statement.setString(index++, query.receiverProfileId().value());
        statement.setString(index++, query.analysisProfileId().value());
        statement.setString(index++, query.startDate().toString());
        statement.setString(index++, query.endDate().toString());
        index = bindExcludedDates(statement, index, query);
        for (VesselClass vesselClass : classes) {
            statement.setString(index++, vesselClass.name());
        }
        statement.setString(index++, query.receiverProfileId().value());
        statement.setString(index++, query.analysisProfileId().value());
        statement.setString(index++, query.startDate().toString());
        statement.setString(index++, query.endDate().toString());
        bindExcludedDates(statement, index, query);
    }

    private static int bindExcludedDates(
            PreparedStatement statement,
            int firstIndex,
            ShipLengthPerformanceQuery query) throws SQLException {
        int index = firstIndex;
        for (java.time.LocalDate date : query.excludedDates().stream()
                .sorted().toList()) {
            statement.setString(index++, date.toString());
        }
        return index;
    }

    private static String exclusionClause(String column, int count) {
        if (count == 0) {
            return "";
        }
        return "AND " + column + " NOT IN ("
                + String.join(", ", Collections.nCopies(count, "?"))
                + ")";
    }

    private static StoredShipLengthPerformance read(
            PreparedStatement statement) throws SQLException {
        try (ResultSet results = statement.executeQuery()) {
            int readyRuns = 0;
            int unreadyRuns = 0;
            int totalVessels = 0;
            int knownLengthVessels = 0;
            boolean totalsRead = false;
            List<StoredShipLengthPerformanceRow> rows = new ArrayList<>();
            while (results.next()) {
                if (!totalsRead) {
                    readyRuns = results.getInt("ready_analysis_run_count");
                    unreadyRuns = results.getInt(
                            "reanalysis_required_run_count");
                    totalVessels = results.getInt(
                            "total_distinct_vessel_count");
                    knownLengthVessels = results.getInt(
                            "known_length_distinct_vessel_count");
                    totalsRead = true;
                }
                String vesselClass = results.getString("vessel_class");
                if (vesselClass == null) {
                    continue;
                }
                rows.add(new StoredShipLengthPerformanceRow(
                        results.getInt("ship_length_band_order"),
                        new DistanceBand(
                                results.getInt("distance_band_index"),
                                results.getDouble("lower_kilometers"),
                                results.getDouble("upper_kilometers")),
                        VesselClass.valueOf(vesselClass),
                        new MetricCounts(
                                results.getLong("observed_count"),
                                results.getLong("missing_count"),
                                results.getDouble("observed_seconds"),
                                results.getDouble("stale_seconds")),
                        results.getInt("distinct_vessel_count"),
                        results.getInt("observation_day_count")));
            }
            return new StoredShipLengthPerformance(
                    readyRuns, unreadyRuns,
                    totalVessels, knownLengthVessels, rows);
        }
    }
}
