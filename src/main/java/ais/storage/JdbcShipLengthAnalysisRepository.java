package ais.storage;

import ais.domain.VesselClass;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Reads a vessel-day analyzed-track reach distribution from the saved daily
 * presence table. Each vessel contributes once per day, at the farthest
 * distance band touched by its analyzed track on that day. Presence may come
 * from received reports, estimated missing positions, or interval duration.
 */
public final class JdbcShipLengthAnalysisRepository {

    private final SqliteDatabase database;

    public JdbcShipLengthAnalysisRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public StoredShipLengthAnalysis query(ShipLengthAnalysisQuery query) {
        Objects.requireNonNull(query, "query");
        List<VesselClass> classes = query.vesselClasses().stream()
                .sorted()
                .toList();
        String placeholders = String.join(", ",
                Collections.nCopies(classes.size(), "?"));
        String excluded = exclusionClause(query.excludedDates().size());
        String common = commonTableExpressions(placeholders, excluded);
        String sql = common + """
                , band_totals AS (
                    SELECT ship_length_band_order, vessel_class,
                           COUNT(*) AS band_vessel_day_count,
                           COUNT(DISTINCT mmsi)
                               AS band_distinct_vessel_count
                      FROM classified
                     GROUP BY ship_length_band_order, vessel_class
                )
                , cells AS (
                    SELECT classified.ship_length_band_order,
                           classified.vessel_class,
                           classified.daily_maximum_distance_band_index,
                           COUNT(*) AS vessel_day_count,
                           COUNT(DISTINCT classified.mmsi)
                               AS cell_distinct_vessel_count,
                           totals.band_vessel_day_count,
                           totals.band_distinct_vessel_count
                      FROM classified
                      JOIN band_totals totals
                        ON totals.ship_length_band_order =
                           classified.ship_length_band_order
                       AND totals.vessel_class = classified.vessel_class
                     GROUP BY classified.ship_length_band_order,
                              classified.vessel_class,
                              classified.daily_maximum_distance_band_index
                ),
                coverage AS (
                    SELECT (
                           SELECT COUNT(DISTINCT presence.analysis_run_id)
                             FROM selected_presence presence
                       ) AS analysis_run_count,
                       COUNT(DISTINCT vessel_class || ':' || mmsi)
                           AS total_distinct_vessel_count,
                       COUNT(DISTINCT CASE
                           WHEN ship_length_band_order <> 6
                           THEN vessel_class || ':' || mmsi
                       END) AS known_length_distinct_vessel_count,
                       COUNT(*) AS total_vessel_day_count,
                       SUM(CASE WHEN ship_length_band_order <> 6
                           THEN 1 ELSE 0 END)
                           AS known_length_vessel_day_count
                      FROM classified
                )
                SELECT coverage.analysis_run_count,
                       coverage.total_distinct_vessel_count,
                       coverage.known_length_distinct_vessel_count,
                       coverage.total_vessel_day_count,
                       coverage.known_length_vessel_day_count,
                       cells.ship_length_band_order,
                       cells.vessel_class,
                       cells.daily_maximum_distance_band_index,
                       cells.vessel_day_count,
                       cells.cell_distinct_vessel_count,
                       cells.band_vessel_day_count,
                       cells.band_distinct_vessel_count
                  FROM coverage
                  LEFT JOIN cells ON 1 = 1
                 ORDER BY cells.ship_length_band_order,
                          cells.vessel_class,
                          cells.daily_maximum_distance_band_index
                """;
        try (Connection connection = database.open()) {
            return readAnalysis(connection, sql, query, classes);
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not read ship-length reception analysis",
                    failure);
        }
    }

    private static String commonTableExpressions(
            String placeholders,
            String excluded) {
        return """
                WITH selected_presence AS (
                    SELECT presence.analysis_run_id,
                           presence.observed_date,
                           presence.distance_band_index,
                           presence.vessel_class,
                           presence.mmsi
                      FROM distance_vessel_presence_day presence
                      JOIN analysis_run run
                        ON run.id = presence.analysis_run_id
                     WHERE run.active = 1
                       AND run.status = 'COMPLETE'
                       AND run.receiver_profile_id = ?
                       AND run.analysis_profile_id = ?
                       AND presence.observed_date >= ?
                       AND presence.observed_date <= ?
                       $EXCLUDED$
                       AND presence.vessel_class IN ($CLASSES$)
                ),
                daily_maximum AS (
                    SELECT observed_date, vessel_class, mmsi,
                           MAX(distance_band_index)
                               AS daily_maximum_distance_band_index
                      FROM selected_presence
                     GROUP BY observed_date, vessel_class, mmsi
                ),
                length_candidates AS (
                    SELECT daily.observed_date,
                           daily.vessel_class,
                           daily.mmsi,
                           daily.daily_maximum_distance_band_index,
                           history.ship_length_meters,
                           ROW_NUMBER() OVER (
                               PARTITION BY daily.observed_date,
                                            daily.vessel_class,
                                            daily.mmsi
                               ORDER BY history.valid_from DESC
                           ) AS length_row_number
                      FROM daily_maximum daily
                      LEFT JOIN vessel_metadata_history history
                        ON history.mmsi = daily.mmsi
                       AND history.vessel_class = daily.vessel_class
                       AND history.ship_length_meters IS NOT NULL
                       AND julianday(history.valid_from) < julianday(
                           daily.observed_date
                               || 'T00:00:00+09:00', '+1 day')
                ),
                classified AS (
                    SELECT candidate.observed_date,
                           candidate.vessel_class,
                           candidate.mmsi,
                           candidate.daily_maximum_distance_band_index,
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
                )
                """.replace("$EXCLUDED$", excluded)
                .replace("$CLASSES$", placeholders);
    }

    private static StoredShipLengthAnalysis readAnalysis(
            Connection connection,
            String sql,
            ShipLengthAnalysisQuery query,
            List<VesselClass> classes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, query, classes);
            try (ResultSet results = statement.executeQuery()) {
                List<StoredShipLengthCell> cells = new ArrayList<>();
                StoredShipLengthCoverage coverage = null;
                while (results.next()) {
                    if (coverage == null) {
                        coverage = new StoredShipLengthCoverage(
                                results.getInt("analysis_run_count"),
                                results.getInt(
                                        "total_distinct_vessel_count"),
                                results.getInt(
                                        "known_length_distinct_vessel_count"),
                                results.getLong("total_vessel_day_count"),
                                results.getLong(
                                        "known_length_vessel_day_count"));
                    }
                    String vesselClass = results.getString("vessel_class");
                    if (vesselClass == null) {
                        continue;
                    }
                    cells.add(new StoredShipLengthCell(
                            results.getInt("ship_length_band_order"),
                            VesselClass.valueOf(vesselClass),
                            results.getInt(
                                    "daily_maximum_distance_band_index"),
                            results.getLong("vessel_day_count"),
                            results.getInt("cell_distinct_vessel_count"),
                            results.getLong("band_vessel_day_count"),
                            results.getInt("band_distinct_vessel_count")));
                }
                if (coverage == null) {
                    coverage = new StoredShipLengthCoverage(
                            0, 0, 0, 0, 0);
                }
                return new StoredShipLengthAnalysis(coverage, cells);
            }
        }
    }

    private static void bind(
            PreparedStatement statement,
            ShipLengthAnalysisQuery query,
            List<VesselClass> classes) throws SQLException {
        int index = 1;
        statement.setString(index++, query.receiverProfileId().value());
        statement.setString(index++, query.analysisProfileId().value());
        statement.setString(index++, query.startDate().toString());
        statement.setString(index++, query.endDate().toString());
        for (java.time.LocalDate date : query.excludedDates().stream()
                .sorted().toList()) {
            statement.setString(index++, date.toString());
        }
        for (VesselClass vesselClass : classes) {
            statement.setString(index++, vesselClass.name());
        }
    }

    private static String exclusionClause(int count) {
        if (count == 0) {
            return "";
        }
        return "AND presence.observed_date NOT IN ("
                + String.join(", ", Collections.nCopies(count, "?"))
                + ")";
    }
}
