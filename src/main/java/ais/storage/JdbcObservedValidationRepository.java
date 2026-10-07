package ais.storage;

import ais.aggregate.MetricCounts;
import ais.domain.AnalysisRunId;
import ais.domain.VesselClass;
import ais.simulation.validation.ObservedValidationCell;
import ais.simulation.validation.ObservedValidationDataset;
import ais.simulation.validation.ValidationCellKey;
import ais.spatial.DistanceBand;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JdbcObservedValidationRepository
        implements ObservedValidationRepository {

    private final SqliteDatabase database;

    public JdbcObservedValidationRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public ObservedValidationDataset load(ObservedValidationQuery query) {
        Objects.requireNonNull(query, "query");
        try (Connection connection = database.open()) {
            List<AnalysisRunId> runs = readRuns(connection, query);
            Map<ValidationCellKey, MutableCell> cells = readTotals(
                    connection, query);
            readDailyRates(connection, query, cells);
            readVesselCounts(connection, query, cells);
            Map<ValidationCellKey, ObservedValidationCell> result =
                    new LinkedHashMap<>();
            cells.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.put(entry.getKey(),
                            entry.getValue().freeze(entry.getKey())));
            return new ObservedValidationDataset(runs, result);
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not load observed validation metrics", failure);
        }
    }

    private static List<AnalysisRunId> readRuns(
            Connection connection,
            ObservedValidationQuery query) throws SQLException {
        String sql = """
                SELECT id
                  FROM analysis_run
                 WHERE source_mode = 'HISTORICAL'
                   AND active = 1
                   AND status = 'COMPLETE'
                   AND receiver_profile_id = ?
                   AND analysis_profile_id = ?
                   AND target_date >= ?
                   AND target_date <= ?
                """ + excludedClause("target_date", query)
                + " ORDER BY target_date, id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCommon(statement, query);
            try (ResultSet results = statement.executeQuery()) {
                List<AnalysisRunId> values = new ArrayList<>();
                while (results.next()) {
                    values.add(AnalysisRunId.parse(results.getString(1)));
                }
                return List.copyOf(values);
            }
        }
    }

    private static Map<ValidationCellKey, MutableCell> readTotals(
            Connection connection,
            ObservedValidationQuery query) throws SQLException {
        String sql = """
                SELECT metric.distance_band_index,
                       MIN(metric.lower_kilometers) AS lower_kilometers,
                       MAX(metric.upper_kilometers) AS upper_kilometers,
                       metric.vessel_class,
                       SUM(metric.observed_count) AS observed_count,
                       SUM(metric.missing_count) AS missing_count,
                       SUM(metric.observed_seconds) AS observed_seconds,
                       SUM(metric.stale_seconds) AS stale_seconds,
                       COUNT(DISTINCT run.target_date) AS observed_days
                  FROM distance_metric_5m metric
                  JOIN analysis_run run
                    ON run.id = metric.analysis_run_id
                 WHERE run.source_mode = 'HISTORICAL'
                   AND run.active = 1
                   AND run.status = 'COMPLETE'
                   AND run.receiver_profile_id = ?
                   AND run.analysis_profile_id = ?
                   AND run.target_date >= ?
                   AND run.target_date <= ?
                """ + excludedClause("run.target_date", query) + """
                 GROUP BY metric.distance_band_index, metric.vessel_class
                 ORDER BY metric.distance_band_index, metric.vessel_class
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCommon(statement, query);
            try (ResultSet results = statement.executeQuery()) {
                Map<ValidationCellKey, MutableCell> values = new HashMap<>();
                while (results.next()) {
                    ValidationCellKey key = key(results);
                    values.put(key, new MutableCell(
                            new MetricCounts(
                                    results.getLong("observed_count"),
                                    results.getLong("missing_count"),
                                    results.getDouble("observed_seconds"),
                                    results.getDouble("stale_seconds")),
                            results.getInt("observed_days")));
                }
                return values;
            }
        }
    }

    private static void readDailyRates(
            Connection connection,
            ObservedValidationQuery query,
            Map<ValidationCellKey, MutableCell> cells) throws SQLException {
        String sql = """
                SELECT run.target_date,
                       metric.distance_band_index,
                       MIN(metric.lower_kilometers) AS lower_kilometers,
                       MAX(metric.upper_kilometers) AS upper_kilometers,
                       metric.vessel_class,
                       SUM(metric.observed_count) AS observed_count,
                       SUM(metric.missing_count) AS missing_count,
                       SUM(metric.observed_seconds) AS observed_seconds,
                       SUM(metric.stale_seconds) AS stale_seconds
                  FROM distance_metric_5m metric
                  JOIN analysis_run run
                    ON run.id = metric.analysis_run_id
                 WHERE run.source_mode = 'HISTORICAL'
                   AND run.active = 1
                   AND run.status = 'COMPLETE'
                   AND run.receiver_profile_id = ?
                   AND run.analysis_profile_id = ?
                   AND run.target_date >= ?
                   AND run.target_date <= ?
                """ + excludedClause("run.target_date", query) + """
                 GROUP BY run.target_date, metric.distance_band_index,
                          metric.vessel_class
                 ORDER BY run.target_date, metric.distance_band_index,
                          metric.vessel_class
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCommon(statement, query);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    MutableCell cell = cells.get(key(results));
                    if (cell == null) {
                        continue;
                    }
                    long observed = results.getLong("observed_count");
                    long missing = results.getLong("missing_count");
                    long expected = Math.addExact(observed, missing);
                    if (expected > 0) {
                        cell.dailyLossRates.add(
                                missing * 100.0 / expected);
                    }
                    double seconds = results.getDouble("observed_seconds");
                    if (seconds > 0.0) {
                        cell.dailyFreshnessRates.add(
                                results.getDouble("stale_seconds")
                                        * 100.0 / seconds);
                    }
                }
            }
        }
    }

    private static void readVesselCounts(
            Connection connection,
            ObservedValidationQuery query,
            Map<ValidationCellKey, MutableCell> cells) throws SQLException {
        String sql = """
                SELECT presence.distance_band_index,
                       presence.vessel_class,
                       COUNT(DISTINCT presence.mmsi) AS vessel_count
                  FROM distance_vessel_presence_day presence
                  JOIN analysis_run run
                    ON run.id = presence.analysis_run_id
                 WHERE run.source_mode = 'HISTORICAL'
                   AND run.active = 1
                   AND run.status = 'COMPLETE'
                   AND run.receiver_profile_id = ?
                   AND run.analysis_profile_id = ?
                   AND run.target_date >= ?
                   AND run.target_date <= ?
                """ + excludedClause("run.target_date", query) + """
                 GROUP BY presence.distance_band_index,
                          presence.vessel_class
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCommon(statement, query);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    int bandIndex = results.getInt("distance_band_index");
                    VesselClass vesselClass = VesselClass.valueOf(
                            results.getString("vessel_class"));
                    MutableCell cell = cells.entrySet().stream()
                            .filter(entry -> entry.getKey().distanceBand()
                                    .index() == bandIndex
                                    && entry.getKey().vesselClass()
                                    == vesselClass)
                            .map(Map.Entry::getValue)
                            .findFirst().orElse(null);
                    if (cell != null) {
                        cell.distinctVessels = results.getInt("vessel_count");
                    }
                }
            }
        }
    }

    private static String excludedClause(
            String column, ObservedValidationQuery query) {
        if (query.excludedDates().isEmpty()) {
            return "";
        }
        return " AND " + column + " NOT IN ("
                + String.join(",", java.util.Collections.nCopies(
                query.excludedDates().size(), "?")) + ")";
    }

    private static void bindCommon(
            PreparedStatement statement,
            ObservedValidationQuery query) throws SQLException {
        int parameter = 1;
        statement.setString(parameter++, query.receiverProfileId().value());
        statement.setString(parameter++, query.analysisProfileId().value());
        statement.setString(parameter++, query.startDate().toString());
        statement.setString(parameter++, query.endDate().toString());
        for (var date : query.excludedDates().stream().sorted().toList()) {
            statement.setString(parameter++, date.toString());
        }
    }

    private static ValidationCellKey key(ResultSet results)
            throws SQLException {
        return new ValidationCellKey(
                new DistanceBand(
                        results.getInt("distance_band_index"),
                        results.getDouble("lower_kilometers"),
                        results.getDouble("upper_kilometers")),
                VesselClass.valueOf(results.getString("vessel_class")));
    }

    private static final class MutableCell {
        private final MetricCounts counts;
        private final int observedDays;
        private final List<Double> dailyLossRates = new ArrayList<>();
        private final List<Double> dailyFreshnessRates = new ArrayList<>();
        private int distinctVessels;

        private MutableCell(MetricCounts counts, int observedDays) {
            this.counts = counts;
            this.observedDays = observedDays;
        }

        private ObservedValidationCell freeze(ValidationCellKey key) {
            return new ObservedValidationCell(
                    key, counts, distinctVessels, observedDays,
                    dailyLossRates, dailyFreshnessRates);
        }
    }
}
