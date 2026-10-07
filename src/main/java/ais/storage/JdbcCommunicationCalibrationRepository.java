package ais.storage;

import ais.domain.AnalysisRunId;
import ais.domain.VesselClass;
import ais.simulation.calibration.CalibrationDataset;
import ais.simulation.calibration.CalibrationDayRow;
import ais.simulation.calibration.CommunicationTrainingRequest;
import ais.spatial.DistanceBand;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JdbcCommunicationCalibrationRepository
        implements CommunicationCalibrationRepository {

    private final SqliteDatabase database;

    public JdbcCommunicationCalibrationRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public CalibrationDataset load(CommunicationTrainingRequest request) {
        Objects.requireNonNull(request, "request");
        try (Connection connection = database.open()) {
            Map<LocalDate, AnalysisRunId> runs = readRuns(
                    connection, request);
            Map<DayCellKey, MutableDayCell> cells = readMetrics(
                    connection, request);
            readVessels(connection, request, cells);
            List<CalibrationDayRow> rows = cells.values().stream()
                    .map(MutableDayCell::toRow)
                    .sorted(Comparator
                            .comparing(CalibrationDayRow::date)
                            .thenComparingInt(row ->
                                    row.distanceBand().index())
                            .thenComparing(CalibrationDayRow::vesselClass))
                    .toList();
            List<LocalDate> missingDates = missingDates(request, runs.keySet());
            return new CalibrationDataset(
                    rows, List.copyOf(runs.values()), missingDates);
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not read communication calibration data", failure);
        }
    }

    private static Map<LocalDate, AnalysisRunId> readRuns(
            Connection connection,
            CommunicationTrainingRequest request) throws SQLException {
        String sql = """
                SELECT id, target_date
                  FROM analysis_run
                 WHERE source_mode = 'HISTORICAL'
                   AND active = 1
                   AND status = 'COMPLETE'
                   AND receiver_profile_id = ?
                   AND analysis_profile_id = ?
                   AND target_date >= ?
                   AND target_date <= ?
                 ORDER BY target_date, id
                """;
        Map<LocalDate, AnalysisRunId> runs = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRequest(statement, request);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    LocalDate date = LocalDate.parse(
                            results.getString("target_date"));
                    if (request.excludedDates().contains(date)) {
                        continue;
                    }
                    AnalysisRunId previous = runs.put(date,
                            AnalysisRunId.parse(results.getString("id")));
                    if (previous != null) {
                        throw new SQLException(
                                "multiple active analysis runs for " + date);
                    }
                }
            }
        }
        return runs;
    }

    private static Map<DayCellKey, MutableDayCell> readMetrics(
            Connection connection,
            CommunicationTrainingRequest request) throws SQLException {
        String sql = """
                SELECT run.target_date,
                       metric.distance_band_index,
                       MIN(metric.lower_kilometers) AS lower_kilometers,
                       MAX(metric.upper_kilometers) AS upper_kilometers,
                       metric.vessel_class,
                       SUM(metric.observed_count) AS observed_count,
                       SUM(metric.missing_count) AS missing_count
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
                 GROUP BY run.target_date,
                          metric.distance_band_index,
                          metric.vessel_class
                 ORDER BY run.target_date,
                          metric.distance_band_index,
                          metric.vessel_class
                """;
        Map<DayCellKey, MutableDayCell> cells = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRequest(statement, request);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    LocalDate date = LocalDate.parse(
                            results.getString("target_date"));
                    if (request.excludedDates().contains(date)) {
                        continue;
                    }
                    VesselClass vesselClass = VesselClass.valueOf(
                            results.getString("vessel_class"));
                    if (vesselClass == VesselClass.UNKNOWN) {
                        continue;
                    }
                    DistanceBand band = new DistanceBand(
                            results.getInt("distance_band_index"),
                            results.getDouble("lower_kilometers"),
                            results.getDouble("upper_kilometers"));
                    DayCellKey key = new DayCellKey(
                            date, band.index(), vesselClass);
                    cells.put(key, new MutableDayCell(
                            date, band, vesselClass,
                            results.getLong("observed_count"),
                            results.getLong("missing_count")));
                }
            }
        }
        return cells;
    }

    private static void readVessels(
            Connection connection,
            CommunicationTrainingRequest request,
            Map<DayCellKey, MutableDayCell> cells) throws SQLException {
        String sql = """
                SELECT run.target_date,
                       presence.distance_band_index,
                       presence.vessel_class,
                       presence.mmsi
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
                 ORDER BY run.target_date,
                          presence.distance_band_index,
                          presence.vessel_class,
                          presence.mmsi
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRequest(statement, request);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    LocalDate date = LocalDate.parse(
                            results.getString("target_date"));
                    if (request.excludedDates().contains(date)) {
                        continue;
                    }
                    VesselClass vesselClass = VesselClass.valueOf(
                            results.getString("vessel_class"));
                    DayCellKey key = new DayCellKey(
                            date,
                            results.getInt("distance_band_index"),
                            vesselClass);
                    MutableDayCell cell = cells.get(key);
                    if (cell != null) {
                        cell.mmsis.add(results.getInt("mmsi"));
                    }
                }
            }
        }
    }

    private static void bindRequest(
            PreparedStatement statement,
            CommunicationTrainingRequest request) throws SQLException {
        statement.setString(1, request.receiverProfileId().value());
        statement.setString(2, request.analysisProfileId().value());
        statement.setString(3, request.startDate().toString());
        statement.setString(4, request.endDate().toString());
    }

    private static List<LocalDate> missingDates(
            CommunicationTrainingRequest request,
            Set<LocalDate> datesWithRuns) {
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate date = request.startDate();
                !date.isAfter(request.endDate());
                date = date.plusDays(1)) {
            if (!request.excludedDates().contains(date)
                    && !datesWithRuns.contains(date)) {
                missing.add(date);
            }
        }
        return List.copyOf(missing);
    }

    private record DayCellKey(
            LocalDate date,
            int distanceBandIndex,
            VesselClass vesselClass) {
    }

    private static final class MutableDayCell {
        private final LocalDate date;
        private final DistanceBand band;
        private final VesselClass vesselClass;
        private final long observed;
        private final long missing;
        private final Set<Integer> mmsis = new HashSet<>();

        private MutableDayCell(
                LocalDate date,
                DistanceBand band,
                VesselClass vesselClass,
                long observed,
                long missing) {
            this.date = date;
            this.band = band;
            this.vesselClass = vesselClass;
            this.observed = observed;
            this.missing = missing;
        }

        private CalibrationDayRow toRow() {
            return new CalibrationDayRow(
                    date, band, vesselClass, observed, missing, mmsis);
        }
    }
}
