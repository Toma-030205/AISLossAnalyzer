package ais.storage;

import ais.aggregate.AggregateKey;
import ais.aggregate.AggregateMetric;
import ais.aggregate.AggregationSnapshot;
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
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
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
            long outside = query.fromInclusive() == null
                    && query.toExclusive() == null
                    ? readOutsideRangeCount(connection, query.runId()) : 0;
            return new AggregationSnapshot(
                    combine(gridCounts, gridPresence),
                    combine(distanceCounts, distancePresence),
                    outside);
        } catch (SQLException failure) {
            throw new StorageException("could not read saved aggregates",
                    failure);
        }
    }

    static void replace(Connection connection, AnalysisRunId runId,
                        AggregateBatch batch) throws SQLException {
        deleteExisting(connection, runId);
        writeGrid(connection, runId, batch.snapshot().gridMetrics());
        writeDistance(connection, runId,
                batch.snapshot().distanceMetrics());
    }

    private static void deleteExisting(Connection connection,
                                       AnalysisRunId runId)
            throws SQLException {
        for (String table : new String[]{
                "cell_vessel_presence_5m",
                "distance_vessel_presence_5m",
                "cell_vessel_presence_day",
                "distance_vessel_presence_day",
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
                }
            }
            metricStatement.executeBatch();
            fiveMinutePresence.executeBatch();
            dailyPresence.executeBatch();
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
