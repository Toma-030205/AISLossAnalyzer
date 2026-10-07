package ais.storage;

import ais.aggregate.MetricCounts;
import ais.simulation.validation.ValidationExperiment;
import ais.simulation.validation.ValidationRunResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

public final class JdbcSimulationExperimentRepository
        implements SimulationExperimentRepository {

    private final TransactionRunner transactions;

    public JdbcSimulationExperimentRepository(SqliteDatabase database) {
        transactions = new TransactionRunner(Objects.requireNonNull(
                database, "database"));
    }

    @Override
    public void save(ValidationExperiment experiment) {
        Objects.requireNonNull(experiment, "experiment");
        transactions.execute(connection -> {
            insertExperiment(connection, experiment);
            insertExcludedDates(connection, experiment);
            insertSourceRuns(connection, experiment);
            for (ValidationRunResult run : experiment.runs()) {
                String runId = UUID.randomUUID().toString();
                insertRun(connection, experiment, runId, run);
                insertMetrics(connection, runId, run);
            }
            insertValidationCells(connection, experiment);
            insertValidationSummaries(connection, experiment);
            return null;
        });
    }

    private static void insertExperiment(
            Connection connection,
            ValidationExperiment experiment) throws SQLException {
        var result = experiment.result();
        var request = result.request();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO simulation_experiment (
                    id, model_id, validation_start_date,
                    validation_end_date, iteration_count, seed_base,
                    status, sensitivity, created_at, completed_at,
                    input_fingerprint, warning_text)
                VALUES (?, ?, ?, ?, ?, ?, 'COMPLETE', ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, result.experimentId().toString());
            statement.setString(2, request.modelId().toString());
            statement.setString(3, request.startDate().toString());
            statement.setString(4, request.endDate().toString());
            statement.setInt(5, request.iterationCount());
            statement.setLong(6, request.seedBase());
            statement.setInt(7, request.sensitivity() ? 1 : 0);
            statement.setString(8, JdbcSupport.instant(result.createdAt()));
            statement.setString(9, JdbcSupport.instant(result.completedAt()));
            statement.setString(10, result.inputFingerprint());
            statement.setString(11, result.warnings().isEmpty() ? null
                    : String.join("\n", result.warnings()));
            statement.executeUpdate();
        }
    }

    private static void insertExcludedDates(
            Connection connection,
            ValidationExperiment experiment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO simulation_experiment_excluded_date (
                    experiment_id, excluded_date, reason)
                VALUES (?, ?, ?)
                """)) {
            for (var date : experiment.result().request().excludedDates()
                    .stream().sorted().toList()) {
                statement.setString(1,
                        experiment.result().experimentId().toString());
                statement.setString(2, date.toString());
                statement.setString(3, experiment.result().request()
                        .sensitivity()
                        ? "感度確認の除外日" : "利用者指定の検証除外日");
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertSourceRuns(
            Connection connection,
            ValidationExperiment experiment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO simulation_experiment_source_run (
                    experiment_id, analysis_run_id)
                VALUES (?, ?)
                """)) {
            for (var runId : experiment.result().observedSourceRunIds()) {
                statement.setString(1,
                        experiment.result().experimentId().toString());
                statement.setString(2, runId.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertRun(
            Connection connection,
            ValidationExperiment experiment,
            String runId,
            ValidationRunResult run) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO simulation_run (
                    id, experiment_id, iteration, seed, model_variant,
                    status, out_of_model_count, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 'COMPLETE', ?, ?, ?)
                """)) {
            statement.setString(1, runId);
            statement.setString(2,
                    experiment.result().experimentId().toString());
            statement.setInt(3, run.iteration());
            statement.setLong(4, run.seed());
            statement.setString(5, run.variant().name());
            statement.setLong(6, run.outOfModelCount());
            statement.setString(7, JdbcSupport.instant(run.startedAt()));
            statement.setString(8, JdbcSupport.instant(run.completedAt()));
            statement.executeUpdate();
        }
    }

    private static void insertMetrics(
            Connection connection,
            String runId,
            ValidationRunResult run) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO simulation_distance_metric (
                    simulation_run_id, distance_band_index,
                    lower_kilometers, upper_kilometers, vessel_class,
                    observed_count, missing_count,
                    observed_seconds, stale_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var entry : run.metrics().entrySet()) {
                var key = entry.getKey();
                MetricCounts counts = entry.getValue();
                statement.setString(1, runId);
                statement.setInt(2, key.distanceBand().index());
                statement.setDouble(3,
                        key.distanceBand().lowerKilometers());
                statement.setDouble(4,
                        key.distanceBand().upperKilometers());
                statement.setString(5, key.vesselClass().name());
                statement.setLong(6, counts.observedCount());
                statement.setLong(7, counts.missingCount());
                statement.setDouble(8, counts.observedSeconds());
                statement.setDouble(9, counts.staleSeconds());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertValidationCells(
            Connection connection,
            ValidationExperiment experiment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO simulation_validation_cell (
                    experiment_id, metric, distance_band_index,
                    lower_kilometers, upper_kilometers, vessel_class,
                    observed_percent, observed_range_lower,
                    observed_range_upper, simulation_mean,
                    simulation_range_lower, simulation_range_upper,
                    baseline_mean, simulation_difference_points,
                    baseline_difference_points, expected_count,
                    distinct_vessels, observed_days, comparison_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?)
                """)) {
            for (var cell : experiment.result().cells()) {
                for (var metric
                        : ais.simulation.validation.ValidationMetric.values()) {
                    var comparison = cell.comparison(metric);
                    var observedRange = comparison.observedDailyRange();
                    var simulation = comparison.simulation();
                    var baseline = comparison.baseline();
                    statement.setString(1,
                            experiment.result().experimentId().toString());
                    statement.setString(2, metric.name());
                    statement.setInt(3, cell.key().distanceBand().index());
                    statement.setDouble(4, cell.key().distanceBand()
                            .lowerKilometers());
                    statement.setDouble(5, cell.key().distanceBand()
                            .upperKilometers());
                    statement.setString(6,
                            cell.key().vesselClass().name());
                    JdbcSupport.setNullableDouble(statement, 7,
                            comparison.observedPercent());
                    JdbcSupport.setNullableDouble(statement, 8,
                            observedRange == null ? null
                                    : observedRange.lower95());
                    JdbcSupport.setNullableDouble(statement, 9,
                            observedRange == null ? null
                                    : observedRange.upper95());
                    JdbcSupport.setNullableDouble(statement, 10,
                            simulation == null ? null : simulation.mean());
                    JdbcSupport.setNullableDouble(statement, 11,
                            simulation == null ? null
                                    : simulation.lower95());
                    JdbcSupport.setNullableDouble(statement, 12,
                            simulation == null ? null
                                    : simulation.upper95());
                    JdbcSupport.setNullableDouble(statement, 13,
                            baseline == null ? null : baseline.mean());
                    JdbcSupport.setNullableDouble(statement, 14,
                            comparison.simulationDifferencePoints());
                    JdbcSupport.setNullableDouble(statement, 15,
                            comparison.baselineDifferencePoints());
                    statement.setLong(16,
                            cell.observedCounts().expectedCount());
                    statement.setInt(17, cell.distinctVesselCount());
                    statement.setInt(18, cell.observationDayCount());
                    statement.setString(19,
                            comparison.status().name());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static void insertValidationSummaries(
            Connection connection,
            ValidationExperiment experiment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO simulation_validation_summary (
                    experiment_id, metric, model_weighted_mae_points,
                    baseline_weighted_mae_points, improvement_percent,
                    comparable_cell_count, matching_cell_count,
                    transition_trend, class_difference_trend)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (var metric
                    : ais.simulation.validation.ValidationMetric.values()) {
                var summary = experiment.result().summary(metric);
                statement.setString(1,
                        experiment.result().experimentId().toString());
                statement.setString(2, metric.name());
                JdbcSupport.setNullableDouble(statement, 3,
                        summary.modelWeightedMaePoints());
                JdbcSupport.setNullableDouble(statement, 4,
                        summary.baselineWeightedMaePoints());
                JdbcSupport.setNullableDouble(statement, 5,
                        summary.improvementPercent());
                statement.setInt(6, summary.comparableCellCount());
                statement.setInt(7,
                        summary.withinObservedVariationCount());
                statement.setString(8, summary.transitionTrend());
                statement.setString(9, summary.classDifferenceTrend());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
