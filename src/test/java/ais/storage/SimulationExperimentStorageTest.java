package ais.storage;

import ais.aggregate.AggregationSnapshot;
import ais.aggregate.MetricCounts;
import ais.analysis.AnalysisRunSummary;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.domain.VesselClass;
import ais.input.history.InputFingerprint;
import ais.simulation.calibration.CommunicationModelCode;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationParameter;
import ais.simulation.calibration.CommunicationTrainingRequest;
import ais.simulation.calibration.ConfidenceInterval;
import ais.simulation.calibration.ParameterApplicability;
import ais.simulation.validation.SimulationExperimentId;
import ais.simulation.validation.StatisticalSummary;
import ais.simulation.validation.ValidationCellKey;
import ais.simulation.validation.ValidationCellResult;
import ais.simulation.validation.ValidationCellStatus;
import ais.simulation.validation.ValidationExperiment;
import ais.simulation.validation.ValidationMetric;
import ais.simulation.validation.ValidationMetricComparison;
import ais.simulation.validation.ValidationMetricSummary;
import ais.simulation.validation.ValidationModelVariant;
import ais.simulation.validation.ValidationRequest;
import ais.simulation.validation.ValidationResult;
import ais.simulation.validation.ValidationRunResult;
import ais.spatial.DistanceBand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationExperimentStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesRunsMetricsComparisonsAndSummariesSeparatelyFromObservedRuns()
            throws Exception {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("validation.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        AnalysisRunId trainingRun = saveObservedRun(
                database, receiver, profile, LocalDate.of(2025, 11, 1));
        AnalysisRunId validationRun = saveObservedRun(
                database, receiver, profile, LocalDate.of(2025, 12, 1));

        CommunicationTrainingRequest trainingRequest =
                new CommunicationTrainingRequest(
                        CommunicationModelCode.CM_E1,
                        LocalDate.of(2025, 11, 1),
                        LocalDate.of(2025, 11, 1), Set.of(),
                        receiver.id(), profile.id(), 10, 42L);
        CommunicationParameter parameter = new CommunicationParameter(
                new DistanceBand(0, 0.0, 5.0), VesselClass.CLASS_A,
                90, 10, 10, 1, 0.1, 0.9, 0.9,
                new ConfidenceInterval(0.8, 0.95),
                ParameterApplicability.DIRECT, 0.9, null, null);
        var definition = new JdbcCommunicationModelRepository(database).save(
                new CommunicationModelDraft(
                        trainingRequest, receiver, profile, "test",
                        List.of(parameter), List.of(trainingRun), List.of(),
                        List.of(), Instant.now()),
                "test model", null);

        ValidationCellKey key = new ValidationCellKey(
                new DistanceBand(0, 0.0, 5.0), VesselClass.CLASS_A);
        MetricCounts counts = new MetricCounts(90, 10, 100.0, 10.0);
        StatisticalSummary stats = new StatisticalSummary(
                10.0, 10.0, 9.0, 11.0, 9.0, 11.0, 2);
        EnumMap<ValidationMetric, ValidationMetricComparison> comparisons =
                new EnumMap<>(ValidationMetric.class);
        for (ValidationMetric metric : ValidationMetric.values()) {
            comparisons.put(metric, new ValidationMetricComparison(
                    10.0, stats, stats, stats, 0.0, 0.0,
                    ValidationCellStatus.MATCH));
        }
        ValidationCellResult cell = new ValidationCellResult(
                key, counts, 5, 1, comparisons);
        EnumMap<ValidationMetric, ValidationMetricSummary> summaries =
                new EnumMap<>(ValidationMetric.class);
        for (ValidationMetric metric : ValidationMetric.values()) {
            summaries.put(metric, new ValidationMetricSummary(
                    metric, 0.0, 0.0, null,
                    1, 1, "再現", "再現"));
        }
        ValidationRequest request = new ValidationRequest(
                definition.id(), LocalDate.of(2025, 12, 1),
                LocalDate.of(2025, 12, 1), Set.of(), 1, 42L, false);
        Instant created = Instant.parse("2026-10-06T00:00:00Z");
        ValidationResult result = new ValidationResult(
                SimulationExperimentId.create(), request, definition,
                created, created.plusSeconds(2), List.of(42L),
                List.of(validationRun), List.of(cell), summaries,
                List.of(), "ef".repeat(32));
        ValidationRunResult run = new ValidationRunResult(
                0, 42L, ValidationModelVariant.CM_E1, 0,
                created, created.plusSeconds(1), Map.of(key, counts));

        new JdbcSimulationExperimentRepository(database).save(
                new ValidationExperiment(result, List.of(run)));

        try (Connection connection = database.open();
                Statement statement = connection.createStatement()) {
            assertCount(statement, "simulation_experiment", 1);
            assertCount(statement, "simulation_run", 1);
            assertCount(statement, "simulation_distance_metric", 1);
            assertCount(statement, "simulation_validation_cell", 2);
            assertCount(statement, "simulation_validation_summary", 2);
            assertCount(statement, "analysis_run", 2);
        }
    }

    private static AnalysisRunId saveObservedRun(
            SqliteDatabase database,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            LocalDate date) {
        Instant start = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        AnalysisRunId id = AnalysisRunId.create();
        AnalysisRun run = new AnalysisRun(
                id, SourceMode.HISTORICAL, date, date + ".ais",
                new InputFingerprint("ab".repeat(32), 100, 1),
                receiver.id(), profile.id(), start);
        AnalysisContext context = new AnalysisContext(
                receiver, profile, SourceMode.HISTORICAL, id, start);
        AnalysisRunSummary summary = new AnalysisRunSummary(
                context, start.plusSeconds(1), 0, 0, 0,
                Map.of(), AggregationSnapshot.empty());
        new AnalysisResultStore(database).replaceCompletedRun(
                run, summary, List.of(), List.of());
        return id;
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Receiver",
                new GeoPosition(34.6, 135.2), 30.0,
                null, null, LocalDate.of(2020, 1, 1), null, null);
    }

    private static void assertCount(
            Statement statement, String table, int expected)
            throws Exception {
        try (ResultSet results = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table)) {
            assertTrue(results.next());
            assertEquals(expected, results.getInt(1));
        }
    }
}
