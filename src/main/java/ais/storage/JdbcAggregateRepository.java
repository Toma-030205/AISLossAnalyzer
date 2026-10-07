package ais.storage;

import ais.aggregate.AggregateKey;
import ais.aggregate.AggregateMetric;
import ais.aggregate.AggregationSnapshot;
import ais.aggregate.DistanceVesselDayKey;
import ais.aggregate.MetricCounts;
import ais.domain.AnalysisRunId;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;
import ais.spatial.GridCellId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JdbcAggregateRepository implements AggregateRepository {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final SqliteDatabase database;
    private final TransactionRunner transactions;

    public JdbcAggregateRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transactions = new TransactionRunner(database);
    }

    @Override
    public void replaceRunAggregates(AnalysisRunId runId,
                                     AggregateBatch batch) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(batch, "batch");
        transactions.execute(connection -> {
            replace(connection, runId, batch);
            return null;
        });
    }

    @Override
    public AggregationSnapshot query(AggregateQuery query) {
        Objects.requireNonNull(query, "query");
        try (Connection connection = database.open()) {
            Map<AggregateKey<GridCellId>, MetricCounts> gridCounts =
                    readGridCounts(connection, query);
            Map<AggregateKey<DistanceBand>, MetricCounts> distanceCounts =
                    readDistanceCounts(connection, query);
            Map<AggregateKey<GridCellId>, Set<Integer>> gridPresence =
                    readGridPresence(connection, query);
            Map<AggregateKey<DistanceBand>, Set<Integer>> distancePresence =
                    readDistancePresence(connection, query, distanceCounts);
            Map<DistanceVesselDayKey, MetricCounts> vesselDayMetrics =
                    readDistanceVesselDayMetrics(connection, query);
            long outside = query.fromInclusive() == null
                    && query.toExclusive() == null
                    ? readOutsideRangeCount(connection, query.runId()) : 0;
            return new AggregationSnapshot(
                    combine(gridCounts, gridPresence),
                    combine(distanceCounts, distancePresence),
                    outside,
                    vesselDayMetrics);
        } catch (SQLException failure) {
            throw new StorageException("could not read saved aggregates",
                    failure);
        }
    }

    /**
     * Reads only the distance data needed by the long-term aggregate screen.
     * Aggregating in SQLite avoids materializing millions of five-minute
     * vessel-presence rows in the Java heap.
     */
    public List<StoredDistanceRollup> queryDistanceRollups(
            AggregateRollupQuery query) {
        Objects.requireNonNull(query, "query");
        List<VesselClass> classes = orderedClasses(query);
        String placeholders = placeholders(classes.size());
        String excluded = exclusionClause(
                "metric.bucket_start", true, query);
        String period = periodExpression(
                "metric.bucket_start", true, query.dimension());
        String sql = """
                SELECT $PERIOD$ AS period_value,
                       metric.distance_band_index,
                       MIN(metric.lower_kilometers) AS lower_kilometers,
                       MAX(metric.upper_kilometers) AS upper_kilometers,
                       metric.vessel_class,
                       SUM(metric.observed_count) AS observed_count,
                       SUM(metric.missing_count) AS missing_count,
                       SUM(metric.observed_seconds) AS observed_seconds,
                       SUM(metric.stale_seconds) AS stale_seconds,
                       COUNT(DISTINCT date(
                           metric.bucket_start, '+9 hours'))
                           AS observation_day_count
                  FROM distance_metric_5m metric
                  JOIN analysis_run run
                    ON run.id = metric.analysis_run_id
                 WHERE run.active = 1
                   AND run.status = 'COMPLETE'
                   AND run.receiver_profile_id = ?
                   AND run.analysis_profile_id = ?
                   AND metric.bucket_start >= ?
                   AND metric.bucket_start < ?
                   $EXCLUDED$
                   AND metric.vessel_class IN ($CLASSES$)
                 GROUP BY period_value, metric.distance_band_index,
                          metric.vessel_class
                 ORDER BY period_value, metric.distance_band_index,
                          metric.vessel_class
                """.replace("$PERIOD$", period)
                .replace("$EXCLUDED$", excluded)
                .replace("$CLASSES$", placeholders);
        try (Connection connection = database.open()) {
            Map<DistanceRollupKey, Integer> vessels =
                    readDistanceRollupVesselCounts(
                            connection, query, classes, placeholders);
            try (PreparedStatement statement = connection.prepareStatement(
                    sql)) {
                bindFiveMinuteRollup(statement, query, classes);
                try (ResultSet results = statement.executeQuery()) {
                    List<StoredDistanceRollup> rollups = new ArrayList<>();
                    while (results.next()) {
                        String periodValue = results.getString("period_value");
                        int bandIndex = results.getInt(
                                "distance_band_index");
                        VesselClass vesselClass = VesselClass.valueOf(
                                results.getString("vessel_class"));
                        DistanceRollupKey key = new DistanceRollupKey(
                                periodValue, bandIndex, vesselClass);
                        rollups.add(new StoredDistanceRollup(
                                periodValue,
                                new DistanceBand(
                                        bandIndex,
                                        results.getDouble(
                                                "lower_kilometers"),
                                        results.getDouble(
                                                "upper_kilometers")),
                                vesselClass,
                                readCounts(results),
                                vessels.getOrDefault(key, 0),
                                results.getInt("observation_day_count")));
                    }
                    return List.copyOf(rollups);
                }
            }
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not read distance aggregate rollups", failure);
        }
    }

    /**
     * Reads hour-of-day rollups directly in SQLite. Hourly presence rows keep
     * distinct-vessel calculation bounded, so only the 24 grouped results
     * cross the JDBC boundary.
     */
    public List<StoredHourRollup> queryHourRollups(
            AggregateRollupQuery query) {
        Objects.requireNonNull(query, "query");
        List<VesselClass> classes = orderedClasses(query);
        String placeholders = placeholders(classes.size());
        String excluded = exclusionClause(
                "metric.bucket_start", true, query);
        String hour = periodExpression(
                "metric.bucket_start", true,
                ais.aggregate.RollupDimension.HOUR_OF_DAY);
        String sql = """
                SELECT $HOUR$ AS hour_value,
                       metric.vessel_class,
                       SUM(metric.observed_count) AS observed_count,
                       SUM(metric.missing_count) AS missing_count,
                       SUM(metric.observed_seconds) AS observed_seconds,
                       SUM(metric.stale_seconds) AS stale_seconds,
                       COUNT(DISTINCT date(
                           metric.bucket_start, '+9 hours'))
                           AS observation_day_count
                  FROM distance_metric_5m metric
                  JOIN analysis_run run
                    ON run.id = metric.analysis_run_id
                 WHERE run.active = 1
                   AND run.status = 'COMPLETE'
                   AND run.receiver_profile_id = ?
                   AND run.analysis_profile_id = ?
                   AND metric.bucket_start >= ?
                   AND metric.bucket_start < ?
                   $EXCLUDED$
                   AND metric.vessel_class IN ($CLASSES$)
                 GROUP BY hour_value, metric.vessel_class
                 ORDER BY hour_value, metric.vessel_class
                """.replace("$HOUR$", hour)
                .replace("$EXCLUDED$", excluded)
                .replace("$CLASSES$", placeholders);
        try (Connection connection = database.open()) {
            Map<HourRollupKey, Integer> vessels =
                    readHourRollupVesselCounts(
                            connection, query, classes, placeholders);
            try (PreparedStatement statement = connection.prepareStatement(
                    sql)) {
                bindFiveMinuteRollup(statement, query, classes);
                try (ResultSet results = statement.executeQuery()) {
                    List<StoredHourRollup> rollups = new ArrayList<>();
                    while (results.next()) {
                        int hourValue = Integer.parseInt(
                                results.getString("hour_value"));
                        VesselClass vesselClass = VesselClass.valueOf(
                                results.getString("vessel_class"));
                        rollups.add(new StoredHourRollup(
                                hourValue,
                                vesselClass,
                                readCounts(results),
                                vessels.getOrDefault(new HourRollupKey(
                                        hourValue, vesselClass), 0),
                                results.getInt("observation_day_count")));
                    }
                    return List.copyOf(rollups);
                }
            }
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not read hour aggregate rollups", failure);
        }
    }

    /**
     * Reads the distance-band by hour-of-day matrix used to distinguish a
     * genuine time-of-day effect from a change in the distance composition.
     */
    public List<StoredDistanceHourRollup> queryDistanceHourRollups(
            AggregateRollupQuery query) {
        Objects.requireNonNull(query, "query");
        List<VesselClass> classes = orderedClasses(query);
        String placeholders = placeholders(classes.size());
        String excluded = exclusionClause(
                "metric.bucket_start", true, query);
        String hour = periodExpression(
                "metric.bucket_start", true,
                ais.aggregate.RollupDimension.HOUR_OF_DAY);
        String sql = """
                SELECT $HOUR$ AS hour_value,
                       metric.distance_band_index,
                       MIN(metric.lower_kilometers) AS lower_kilometers,
                       MAX(metric.upper_kilometers) AS upper_kilometers,
                       metric.vessel_class,
                       SUM(metric.observed_count) AS observed_count,
                       SUM(metric.missing_count) AS missing_count,
                       SUM(metric.observed_seconds) AS observed_seconds,
                       SUM(metric.stale_seconds) AS stale_seconds,
                       COUNT(DISTINCT date(
                           metric.bucket_start, '+9 hours'))
                           AS observation_day_count
                  FROM distance_metric_5m metric
                  JOIN analysis_run run
                    ON run.id = metric.analysis_run_id
                 WHERE run.active = 1
                   AND run.status = 'COMPLETE'
                   AND run.receiver_profile_id = ?
                   AND run.analysis_profile_id = ?
                   AND metric.bucket_start >= ?
                   AND metric.bucket_start < ?
                   $EXCLUDED$
                   AND metric.vessel_class IN ($CLASSES$)
                 GROUP BY hour_value, metric.distance_band_index,
                          metric.vessel_class
                 ORDER BY hour_value, metric.distance_band_index,
                          metric.vessel_class
                """.replace("$HOUR$", hour)
                .replace("$EXCLUDED$", excluded)
                .replace("$CLASSES$", placeholders);
        try (Connection connection = database.open()) {
            Map<DistanceHourRollupKey, Integer> vessels =
                    readDistanceHourRollupVesselCounts(
                            connection, query, classes, placeholders);
            try (PreparedStatement statement = connection.prepareStatement(
                    sql)) {
                bindFiveMinuteRollup(statement, query, classes);
                try (ResultSet results = statement.executeQuery()) {
                    List<StoredDistanceHourRollup> rollups =
                            new ArrayList<>();
                    while (results.next()) {
                        int hourValue = Integer.parseInt(
                                results.getString("hour_value"));
                        int bandIndex = results.getInt(
                                "distance_band_index");
                        VesselClass vesselClass = VesselClass.valueOf(
                                results.getString("vessel_class"));
                        DistanceHourRollupKey key =
                                new DistanceHourRollupKey(
                                        hourValue, bandIndex, vesselClass);
                        rollups.add(new StoredDistanceHourRollup(
                                hourValue,
                                new DistanceBand(
                                        bandIndex,
                                        results.getDouble(
                                                "lower_kilometers"),
                                        results.getDouble(
                                                "upper_kilometers")),
                                vesselClass,
                                readCounts(results),
                                vessels.getOrDefault(key, 0),
                                results.getInt("observation_day_count")));
                    }
                    return List.copyOf(rollups);
                }
            }
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not read distance-by-hour aggregate rollups",
                    failure);
        }
    }

    private static Map<DistanceRollupKey, Integer>
            readDistanceRollupVesselCounts(
                    Connection connection,
                    AggregateRollupQuery query,
                    List<VesselClass> classes,
                    String placeholders) throws SQLException {
        String period = periodExpression(
                "presence.observed_date", false, query.dimension());
        String excluded = exclusionClause(
                "presence.observed_date", false, query);
        String sql = """
                SELECT $PERIOD$ AS period_value,
                       presence.distance_band_index,
                       presence.vessel_class,
                       COUNT(DISTINCT presence.mmsi) AS vessel_count
                  FROM distance_vessel_presence_day presence
                  JOIN analysis_run run
                    ON run.id = presence.analysis_run_id
                 WHERE run.active = 1
                   AND run.status = 'COMPLETE'
                   AND run.receiver_profile_id = ?
                   AND run.analysis_profile_id = ?
                   AND presence.observed_date >= ?
                   AND presence.observed_date < ?
                   $EXCLUDED$
                   AND presence.vessel_class IN ($CLASSES$)
                 GROUP BY period_value, presence.distance_band_index,
                          presence.vessel_class
                """.replace("$PERIOD$", period)
                .replace("$EXCLUDED$", excluded)
                .replace("$CLASSES$", placeholders);
        Map<DistanceRollupKey, Integer> counts = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindDailyRollup(statement, query, classes);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    DistanceRollupKey key = new DistanceRollupKey(
                            results.getString("period_value"),
                            results.getInt("distance_band_index"),
                            VesselClass.valueOf(
                                    results.getString("vessel_class")));
                    counts.put(key, results.getInt("vessel_count"));
                }
            }
        }
        return counts;
    }

    private static Map<HourRollupKey, Integer>
            readHourRollupVesselCounts(
                    Connection connection,
                    AggregateRollupQuery query,
                    List<VesselClass> classes,
                    String placeholders) throws SQLException {
        String excluded = exclusionClause(
                "presence.observed_date", false, query);
        String sql = """
                SELECT presence.hour_of_day AS hour_value,
                       presence.vessel_class,
                       COUNT(DISTINCT presence.mmsi) AS vessel_count
                  FROM distance_vessel_presence_hour presence
                  JOIN analysis_run run
                    ON run.id = presence.analysis_run_id
                 WHERE run.active = 1
                   AND run.status = 'COMPLETE'
                   AND run.receiver_profile_id = ?
                   AND run.analysis_profile_id = ?
                   AND presence.observed_date >= ?
                   AND presence.observed_date < ?
                   $EXCLUDED$
                   AND presence.vessel_class IN ($CLASSES$)
                 GROUP BY hour_value, presence.vessel_class
                """.replace("$EXCLUDED$", excluded)
                .replace("$CLASSES$", placeholders);
        Map<HourRollupKey, Integer> counts = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindDailyRollup(statement, query, classes);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    HourRollupKey key = new HourRollupKey(
                            Integer.parseInt(results.getString("hour_value")),
                            VesselClass.valueOf(
                                    results.getString("vessel_class")));
                    counts.put(key, results.getInt("vessel_count"));
                }
            }
        }
        return counts;
    }

    private static Map<DistanceHourRollupKey, Integer>
            readDistanceHourRollupVesselCounts(
                    Connection connection,
                    AggregateRollupQuery query,
                    List<VesselClass> classes,
                    String placeholders) throws SQLException {
        String excluded = exclusionClause(
                "presence.observed_date", false, query);
        String sql = """
                SELECT presence.hour_of_day AS hour_value,
                       presence.distance_band_index,
                       presence.vessel_class,
                       COUNT(DISTINCT presence.mmsi) AS vessel_count
                  FROM distance_vessel_presence_hour presence
                  JOIN analysis_run run
                    ON run.id = presence.analysis_run_id
                 WHERE run.active = 1
                   AND run.status = 'COMPLETE'
                   AND run.receiver_profile_id = ?
                   AND run.analysis_profile_id = ?
                   AND presence.observed_date >= ?
                   AND presence.observed_date < ?
                   $EXCLUDED$
                   AND presence.vessel_class IN ($CLASSES$)
                 GROUP BY hour_value, presence.distance_band_index,
                          presence.vessel_class
                """.replace("$EXCLUDED$", excluded)
                .replace("$CLASSES$", placeholders);
        Map<DistanceHourRollupKey, Integer> counts = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindDailyRollup(statement, query, classes);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    DistanceHourRollupKey key =
                            new DistanceHourRollupKey(
                                    Integer.parseInt(results.getString(
                                            "hour_value")),
                                    results.getInt("distance_band_index"),
                                    VesselClass.valueOf(results.getString(
                                            "vessel_class")));
                    counts.put(key, results.getInt("vessel_count"));
                }
            }
        }
        return counts;
    }

    private static List<VesselClass> orderedClasses(
            AggregateRollupQuery query) {
        return query.vesselClasses().stream()
                .sorted()
                .toList();
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static void bindFiveMinuteRollup(
            PreparedStatement statement,
            AggregateRollupQuery query,
            List<VesselClass> classes) throws SQLException {
        int index = bindProfile(statement, query);
        statement.setString(index++, JdbcSupport.instant(
                query.fromInclusive()));
        statement.setString(index++, JdbcSupport.instant(
                query.toExclusive()));
        index = bindExcludedDates(statement, index, query);
        bindClasses(statement, index, classes);
    }

    private static void bindDailyRollup(
            PreparedStatement statement,
            AggregateRollupQuery query,
            List<VesselClass> classes) throws SQLException {
        int index = bindProfile(statement, query);
        statement.setString(index++, query.fromInclusive()
                .atZone(JAPAN).toLocalDate().toString());
        statement.setString(index++, query.toExclusive()
                .atZone(JAPAN).toLocalDate().toString());
        index = bindExcludedDates(statement, index, query);
        bindClasses(statement, index, classes);
    }

    private static int bindExcludedDates(
            PreparedStatement statement,
            int firstIndex,
            AggregateRollupQuery query) throws SQLException {
        int index = firstIndex;
        for (java.time.LocalDate date : query.excludedDates().stream()
                .sorted().toList()) {
            statement.setString(index++, date.toString());
        }
        return index;
    }

    private static String exclusionClause(
            String value,
            boolean instant,
            AggregateRollupQuery query) {
        if (query.excludedDates().isEmpty()) {
            return "";
        }
        String expression = instant
                ? "date(" + value + ", '+9 hours')"
                : value;
        return "AND " + expression + " NOT IN ("
                + placeholders(query.excludedDates().size()) + ")";
    }

    private static int bindProfile(
            PreparedStatement statement,
            AggregateRollupQuery query) throws SQLException {
        statement.setString(1, query.receiverProfileId().value());
        statement.setString(2, query.analysisProfileId().value());
        return 3;
    }

    private static void bindClasses(
            PreparedStatement statement,
            int firstIndex,
            List<VesselClass> classes) throws SQLException {
        int index = firstIndex;
        for (VesselClass vesselClass : classes) {
            statement.setString(index++, vesselClass.name());
        }
    }

    private static String periodExpression(
            String value,
            boolean instant,
            ais.aggregate.RollupDimension dimension) {
        String zoneModifier = instant ? ", '+9 hours'" : "";
        return switch (dimension) {
            case DAY -> instant
                    ? "date(" + value + zoneModifier + ")"
                    : value;
            case DAY_OF_WEEK -> "CASE strftime('%w', " + value
                    + zoneModifier + ") "
                    + "WHEN '0' THEN 'SUNDAY' "
                    + "WHEN '1' THEN 'MONDAY' "
                    + "WHEN '2' THEN 'TUESDAY' "
                    + "WHEN '3' THEN 'WEDNESDAY' "
                    + "WHEN '4' THEN 'THURSDAY' "
                    + "WHEN '5' THEN 'FRIDAY' "
                    + "WHEN '6' THEN 'SATURDAY' END";
            case MONTH -> "strftime('%Y-%m', " + value
                    + zoneModifier + ")";
            case YEAR -> "strftime('%Y', " + value
                    + zoneModifier + ")";
            case HOUR_OF_DAY -> "strftime('%H', " + value
                    + zoneModifier + ")";
        };
    }

    private record DistanceRollupKey(
            String periodValue,
            int distanceBandIndex,
            VesselClass vesselClass) {
    }

    private record HourRollupKey(
            int hour,
            VesselClass vesselClass) {
    }

    private record DistanceHourRollupKey(
            int hour,
            int distanceBandIndex,
            VesselClass vesselClass) {
    }

    static void replace(Connection connection, AnalysisRunId runId,
                        AggregateBatch batch) throws SQLException {
        deleteExisting(connection, runId);
        writeGrid(connection, runId, batch.snapshot().gridMetrics());
        writeDistance(connection, runId,
                batch.snapshot().distanceMetrics());
        writeDistanceVesselDayMetrics(connection, runId,
                batch.snapshot().distanceVesselDayMetrics());
        markVesselMetricsReady(connection, runId);
    }

    private static void deleteExisting(Connection connection,
                                       AnalysisRunId runId)
            throws SQLException {
        for (String table : new String[]{
                "cell_vessel_presence_5m",
                "distance_vessel_presence_5m",
                "distance_vessel_presence_hour",
                "cell_vessel_presence_day",
                "distance_vessel_presence_day",
                "distance_vessel_metric_day",
                "cell_metric_5m",
                "distance_metric_5m"}) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE analysis_run_id = ?")) {
                statement.setString(1, runId.toString());
                statement.executeUpdate();
            }
        }
    }

    private static void writeGrid(Connection connection, AnalysisRunId runId,
                                  Map<AggregateKey<GridCellId>,
                                          AggregateMetric> metrics)
            throws SQLException {
        try (PreparedStatement metricStatement = connection.prepareStatement("""
                INSERT INTO cell_metric_5m (
                    analysis_run_id, bucket_start, utm_zone, grid_column,
                    grid_row, vessel_class, observed_count, missing_count,
                    observed_seconds, stale_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
                PreparedStatement fiveMinutePresence =
                        connection.prepareStatement("""
                        INSERT INTO cell_vessel_presence_5m (
                            analysis_run_id, bucket_start, utm_zone,
                            grid_column, grid_row, vessel_class, mmsi)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """);
                PreparedStatement dailyPresence =
                        connection.prepareStatement("""
                        INSERT OR IGNORE INTO cell_vessel_presence_day (
                            analysis_run_id, observed_date, utm_zone,
                            grid_column, grid_row, vessel_class, mmsi)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
            for (Map.Entry<AggregateKey<GridCellId>, AggregateMetric> entry
                    : metrics.entrySet()) {
                AggregateKey<GridCellId> key = entry.getKey();
                GridCellId cell = key.spatialKey();
                MetricCounts counts = entry.getValue().counts();
                bindGridKey(metricStatement, runId, key, cell);
                metricStatement.setLong(7, counts.observedCount());
                metricStatement.setLong(8, counts.missingCount());
                metricStatement.setDouble(9, counts.observedSeconds());
                metricStatement.setDouble(10, counts.staleSeconds());
                metricStatement.addBatch();
                for (int mmsi : entry.getValue().vesselMmsis()) {
                    bindGridKey(fiveMinutePresence, runId, key, cell);
                    fiveMinutePresence.setInt(7, mmsi);
                    fiveMinutePresence.addBatch();

                    dailyPresence.setString(1, runId.toString());
                    dailyPresence.setString(2,
                            key.bucketStart().atZone(JAPAN)
                                    .toLocalDate().toString());
                    dailyPresence.setInt(3, cell.zone());
                    dailyPresence.setLong(4, cell.column());
                    dailyPresence.setLong(5, cell.row());
                    dailyPresence.setString(6, key.vesselClass().name());
                    dailyPresence.setInt(7, mmsi);
                    dailyPresence.addBatch();
                }
            }
            metricStatement.executeBatch();
            fiveMinutePresence.executeBatch();
            dailyPresence.executeBatch();
        }
    }

    private static void bindGridKey(PreparedStatement statement,
                                    AnalysisRunId runId,
                                    AggregateKey<GridCellId> key,
                                    GridCellId cell) throws SQLException {
        statement.setString(1, runId.toString());
        statement.setString(2, JdbcSupport.instant(key.bucketStart()));
        statement.setInt(3, cell.zone());
        statement.setLong(4, cell.column());
        statement.setLong(5, cell.row());
        statement.setString(6, key.vesselClass().name());
    }

    private static void writeDistance(Connection connection,
                                      AnalysisRunId runId,
                                      Map<AggregateKey<DistanceBand>,
                                              AggregateMetric> metrics)
            throws SQLException {
        try (PreparedStatement metricStatement = connection.prepareStatement("""
                INSERT INTO distance_metric_5m (
                    analysis_run_id, bucket_start, distance_band_index,
                    lower_kilometers, upper_kilometers, vessel_class,
                    observed_count, missing_count, observed_seconds,
                    stale_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
                PreparedStatement fiveMinutePresence =
                        connection.prepareStatement("""
                        INSERT INTO distance_vessel_presence_5m (
                            analysis_run_id, bucket_start,
                            distance_band_index, vessel_class, mmsi)
                        VALUES (?, ?, ?, ?, ?)
                        """);
                PreparedStatement dailyPresence =
                        connection.prepareStatement("""
                        INSERT OR IGNORE INTO distance_vessel_presence_day (
                            analysis_run_id, observed_date,
                            distance_band_index, vessel_class, mmsi)
                        VALUES (?, ?, ?, ?, ?)
                        """);
                PreparedStatement hourlyPresence =
                        connection.prepareStatement("""
                        INSERT OR IGNORE INTO distance_vessel_presence_hour (
                            analysis_run_id, observed_date, hour_of_day,
                            distance_band_index, vessel_class, mmsi)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
            for (Map.Entry<AggregateKey<DistanceBand>, AggregateMetric> entry
                    : metrics.entrySet()) {
                AggregateKey<DistanceBand> key = entry.getKey();
                DistanceBand band = key.spatialKey();
                MetricCounts counts = entry.getValue().counts();
                metricStatement.setString(1, runId.toString());
                metricStatement.setString(2,
                        JdbcSupport.instant(key.bucketStart()));
                metricStatement.setInt(3, band.index());
                metricStatement.setDouble(4, band.lowerKilometers());
                metricStatement.setDouble(5, band.upperKilometers());
                metricStatement.setString(6, key.vesselClass().name());
                metricStatement.setLong(7, counts.observedCount());
                metricStatement.setLong(8, counts.missingCount());
                metricStatement.setDouble(9, counts.observedSeconds());
                metricStatement.setDouble(10, counts.staleSeconds());
                metricStatement.addBatch();
                for (int mmsi : entry.getValue().vesselMmsis()) {
                    fiveMinutePresence.setString(1, runId.toString());
                    fiveMinutePresence.setString(2,
                            JdbcSupport.instant(key.bucketStart()));
                    fiveMinutePresence.setInt(3, band.index());
                    fiveMinutePresence.setString(4,
                            key.vesselClass().name());
                    fiveMinutePresence.setInt(5, mmsi);
                    fiveMinutePresence.addBatch();

                    dailyPresence.setString(1, runId.toString());
                    dailyPresence.setString(2,
                            key.bucketStart().atZone(JAPAN)
                                    .toLocalDate().toString());
                    dailyPresence.setInt(3, band.index());
                    dailyPresence.setString(4,
                            key.vesselClass().name());
                    dailyPresence.setInt(5, mmsi);
                    dailyPresence.addBatch();

                    hourlyPresence.setString(1, runId.toString());
                    hourlyPresence.setString(2,
                            key.bucketStart().atZone(JAPAN)
                                    .toLocalDate().toString());
                    hourlyPresence.setInt(3,
                            key.bucketStart().atZone(JAPAN).getHour());
                    hourlyPresence.setInt(4, band.index());
                    hourlyPresence.setString(5,
                            key.vesselClass().name());
                    hourlyPresence.setInt(6, mmsi);
                    hourlyPresence.addBatch();
                }
            }
            metricStatement.executeBatch();
            fiveMinutePresence.executeBatch();
            dailyPresence.executeBatch();
            hourlyPresence.executeBatch();
        }
    }

    private static void writeDistanceVesselDayMetrics(
            Connection connection,
            AnalysisRunId runId,
            Map<DistanceVesselDayKey, MetricCounts> metrics)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO distance_vessel_metric_day (
                    analysis_run_id, observed_date, distance_band_index,
                    lower_kilometers, upper_kilometers, vessel_class, mmsi,
                    observed_count, missing_count, observed_seconds,
                    stale_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (Map.Entry<DistanceVesselDayKey, MetricCounts> entry
                    : metrics.entrySet()) {
                DistanceVesselDayKey key = entry.getKey();
                DistanceBand band = key.distanceBand();
                MetricCounts counts = entry.getValue();
                statement.setString(1, runId.toString());
                statement.setString(2, key.observedDate().toString());
                statement.setInt(3, band.index());
                statement.setDouble(4, band.lowerKilometers());
                statement.setDouble(5, band.upperKilometers());
                statement.setString(6, key.vesselClass().name());
                statement.setInt(7, key.mmsi());
                statement.setLong(8, counts.observedCount());
                statement.setLong(9, counts.missingCount());
                statement.setDouble(10, counts.observedSeconds());
                statement.setDouble(11, counts.staleSeconds());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void markVesselMetricsReady(
            Connection connection,
            AnalysisRunId runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE analysis_run
                   SET vessel_metric_ready = 1
                 WHERE id = ?
                """)) {
            statement.setString(1, runId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("analysis run is missing: " + runId);
            }
        }
    }

    private static Map<AggregateKey<GridCellId>, MetricCounts> readGridCounts(
            Connection connection, AggregateQuery query) throws SQLException {
        String sql = """
                SELECT bucket_start, utm_zone, grid_column, grid_row,
                       vessel_class, observed_count, missing_count,
                       observed_seconds, stale_seconds
                  FROM cell_metric_5m
                 WHERE analysis_run_id = ?
                """ + timeClause(query) + " ORDER BY bucket_start";
        Map<AggregateKey<GridCellId>, MetricCounts> metrics = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindQuery(statement, query);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    VesselClass vesselClass = VesselClass.valueOf(
                            results.getString("vessel_class"));
                    if (!query.vesselClasses().contains(vesselClass)) {
                        continue;
                    }
                    AggregateKey<GridCellId> key = new AggregateKey<>(
                            Instant.parse(results.getString("bucket_start")),
                            new GridCellId(results.getInt("utm_zone"),
                                    results.getLong("grid_column"),
                                    results.getLong("grid_row")),
                            vesselClass);
                    metrics.put(key, readCounts(results));
                }
            }
        }
        return metrics;
    }

    private static Map<DistanceVesselDayKey, MetricCounts>
            readDistanceVesselDayMetrics(
                    Connection connection,
                    AggregateQuery query) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT observed_date, distance_band_index,
                       lower_kilometers, upper_kilometers,
                       vessel_class, mmsi, observed_count, missing_count,
                       observed_seconds, stale_seconds
                  FROM distance_vessel_metric_day
                 WHERE analysis_run_id = ?
                """);
        LocalDate from = query.fromInclusive() == null ? null
                : query.fromInclusive().atZone(JAPAN).toLocalDate();
        LocalDate to = query.toExclusive() == null ? null
                : query.toExclusive().atZone(JAPAN).toLocalDate();
        if (from != null) {
            sql.append(" AND observed_date >= ?");
        }
        if (to != null) {
            sql.append(" AND observed_date < ?");
        }
        sql.append(" ORDER BY observed_date, distance_band_index, "
                + "vessel_class, mmsi");
        Map<DistanceVesselDayKey, MetricCounts> metrics = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                sql.toString())) {
            int index = 1;
            statement.setString(index++, query.runId().toString());
            if (from != null) {
                statement.setString(index++, from.toString());
            }
            if (to != null) {
                statement.setString(index, to.toString());
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    VesselClass vesselClass = VesselClass.valueOf(
                            results.getString("vessel_class"));
                    if (!query.vesselClasses().contains(vesselClass)) {
                        continue;
                    }
                    DistanceVesselDayKey key = new DistanceVesselDayKey(
                            LocalDate.parse(results.getString(
                                    "observed_date")),
                            new DistanceBand(
                                    results.getInt("distance_band_index"),
                                    results.getDouble("lower_kilometers"),
                                    results.getDouble("upper_kilometers")),
                            vesselClass,
                            results.getInt("mmsi"));
                    metrics.put(key, readCounts(results));
                }
            }
        }
        return Map.copyOf(metrics);
    }

    private static Map<AggregateKey<DistanceBand>, MetricCounts>
            readDistanceCounts(Connection connection, AggregateQuery query)
            throws SQLException {
        String sql = """
                SELECT bucket_start, distance_band_index, lower_kilometers,
                       upper_kilometers, vessel_class, observed_count,
                       missing_count, observed_seconds, stale_seconds
                  FROM distance_metric_5m
                 WHERE analysis_run_id = ?
                """ + timeClause(query) + " ORDER BY bucket_start";
        Map<AggregateKey<DistanceBand>, MetricCounts> metrics = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindQuery(statement, query);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    VesselClass vesselClass = VesselClass.valueOf(
                            results.getString("vessel_class"));
                    if (!query.vesselClasses().contains(vesselClass)) {
                        continue;
                    }
                    AggregateKey<DistanceBand> key = new AggregateKey<>(
                            Instant.parse(results.getString("bucket_start")),
                            new DistanceBand(
                                    results.getInt("distance_band_index"),
                                    results.getDouble("lower_kilometers"),
                                    results.getDouble("upper_kilometers")),
                            vesselClass);
                    metrics.put(key, readCounts(results));
                }
            }
        }
        return metrics;
    }

    private static Map<AggregateKey<GridCellId>, Set<Integer>>
            readGridPresence(Connection connection, AggregateQuery query)
            throws SQLException {
        String sql = """
                SELECT bucket_start, utm_zone, grid_column, grid_row,
                       vessel_class, mmsi
                  FROM cell_vessel_presence_5m
                 WHERE analysis_run_id = ?
                """ + timeClause(query);
        Map<AggregateKey<GridCellId>, Set<Integer>> presence = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindQuery(statement, query);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    VesselClass vesselClass = VesselClass.valueOf(
                            results.getString("vessel_class"));
                    if (!query.vesselClasses().contains(vesselClass)) {
                        continue;
                    }
                    AggregateKey<GridCellId> key = new AggregateKey<>(
                            Instant.parse(results.getString("bucket_start")),
                            new GridCellId(results.getInt("utm_zone"),
                                    results.getLong("grid_column"),
                                    results.getLong("grid_row")),
                            vesselClass);
                    presence.computeIfAbsent(key, ignored -> new HashSet<>())
                            .add(results.getInt("mmsi"));
                }
            }
        }
        return presence;
    }

    private static Map<AggregateKey<DistanceBand>, Set<Integer>>
            readDistancePresence(Connection connection, AggregateQuery query,
                    Map<AggregateKey<DistanceBand>, MetricCounts> counts)
            throws SQLException {
        Map<String, DistanceBand> bands = new HashMap<>();
        for (AggregateKey<DistanceBand> key : counts.keySet()) {
            bands.put(key.bucketStart() + ":" + key.spatialKey().index()
                    + ":" + key.vesselClass(), key.spatialKey());
        }
        String sql = """
                SELECT bucket_start, distance_band_index, vessel_class, mmsi
                  FROM distance_vessel_presence_5m
                 WHERE analysis_run_id = ?
                """ + timeClause(query);
        Map<AggregateKey<DistanceBand>, Set<Integer>> presence =
                new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindQuery(statement, query);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    VesselClass vesselClass = VesselClass.valueOf(
                            results.getString("vessel_class"));
                    if (!query.vesselClasses().contains(vesselClass)) {
                        continue;
                    }
                    Instant bucket = Instant.parse(
                            results.getString("bucket_start"));
                    int index = results.getInt("distance_band_index");
                    DistanceBand band = bands.get(bucket + ":" + index
                            + ":" + vesselClass);
                    if (band == null) {
                        continue;
                    }
                    AggregateKey<DistanceBand> key = new AggregateKey<>(
                            bucket, band, vesselClass);
                    presence.computeIfAbsent(key, ignored -> new HashSet<>())
                            .add(results.getInt("mmsi"));
                }
            }
        }
        return presence;
    }

    private static String timeClause(AggregateQuery query) {
        StringBuilder sql = new StringBuilder();
        if (query.fromInclusive() != null) {
            sql.append(" AND bucket_start >= ?");
        }
        if (query.toExclusive() != null) {
            sql.append(" AND bucket_start < ?");
        }
        return sql.toString();
    }

    private static void bindQuery(PreparedStatement statement,
                                  AggregateQuery query) throws SQLException {
        int index = 1;
        statement.setString(index++, query.runId().toString());
        if (query.fromInclusive() != null) {
            statement.setString(index++,
                    JdbcSupport.instant(query.fromInclusive()));
        }
        if (query.toExclusive() != null) {
            statement.setString(index,
                    JdbcSupport.instant(query.toExclusive()));
        }
    }

    private static MetricCounts readCounts(ResultSet results)
            throws SQLException {
        return new MetricCounts(
                results.getLong("observed_count"),
                results.getLong("missing_count"),
                results.getDouble("observed_seconds"),
                results.getDouble("stale_seconds"));
    }

    private static long readOutsideRangeCount(Connection connection,
                                              AnalysisRunId runId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT outside_distance_range_count
                  FROM analysis_run WHERE id = ?
                """)) {
            statement.setString(1, runId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : 0;
            }
        }
    }

    private static <S> Map<AggregateKey<S>, AggregateMetric> combine(
            Map<AggregateKey<S>, MetricCounts> counts,
            Map<AggregateKey<S>, Set<Integer>> presence) {
        Map<AggregateKey<S>, AggregateMetric> combined = new HashMap<>();
        counts.forEach((key, value) -> combined.put(key,
                new AggregateMetric(value,
                        presence.getOrDefault(key, Set.of()))));
        return Map.copyOf(combined);
    }
}
