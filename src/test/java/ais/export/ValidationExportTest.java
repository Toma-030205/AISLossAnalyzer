package ais.export;

import ais.aggregate.MetricCounts;
import ais.domain.AnalysisProfileId;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.simulation.calibration.CommunicationModelCode;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelId;
import ais.simulation.validation.SimulationExperimentId;
import ais.simulation.validation.StatisticalSummary;
import ais.simulation.validation.ValidationCellKey;
import ais.simulation.validation.ValidationCellResult;
import ais.simulation.validation.ValidationCellStatus;
import ais.simulation.validation.ValidationMetric;
import ais.simulation.validation.ValidationMetricComparison;
import ais.simulation.validation.ValidationMetricSummary;
import ais.simulation.validation.ValidationRequest;
import ais.simulation.validation.ValidationResult;
import ais.spatial.DistanceBand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationExportTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsTraceableCsvAndComparisonPng() throws Exception {
        ValidationResult result = result();
        Path csv = temporaryDirectory.resolve("validation.csv");
        Path png = temporaryDirectory.resolve("validation.png");

        new ValidationCsvExporter().write(csv, result);
        new ValidationChartPngExporter().write(
                png, result, ValidationMetric.ESTIMATED_LOSS,
                900, 550);

        String text = Files.readString(csv, StandardCharsets.UTF_8);
        assertTrue(text.contains("実験ID"));
        assertTrue(text.contains("CM-E1平均%"));
        assertTrue(text.contains("fingerprint"));
        byte[] signature = Files.readAllBytes(png);
        assertEquals((byte) 0x89, signature[0]);
        assertEquals((byte) 'P', signature[1]);
        assertEquals(6, new ValidationChartRenderer().create(
                result, ValidationMetric.ESTIMATED_LOSS)
                .getXYPlot().getDataset().getSeriesCount());
    }

    private static ValidationResult result() {
        CommunicationModelId modelId = CommunicationModelId.parse(
                "00000000-0000-0000-0000-000000000501");
        CommunicationModelDefinition definition =
                new CommunicationModelDefinition(
                        modelId, CommunicationModelCode.CM_E1, 1,
                        "model", new ReceiverProfileId("receiver"),
                        new AnalysisProfileId("profile"),
                        LocalDate.of(2025, 11, 1),
                        LocalDate.of(2025, 11, 30), "v1", 1000, 42,
                        Instant.EPOCH, null);
        StatisticalSummary statistics = new StatisticalSummary(
                10.0, 10.0, 8.0, 12.0, 8.2, 11.8, 30);
        EnumMap<ValidationMetric, ValidationMetricComparison> comparisons =
                new EnumMap<>(ValidationMetric.class);
        for (ValidationMetric metric : ValidationMetric.values()) {
            comparisons.put(metric, new ValidationMetricComparison(
                    10.0, statistics, statistics, statistics,
                    0.0, 0.0, ValidationCellStatus.MATCH));
        }
        ValidationCellResult cell = new ValidationCellResult(
                new ValidationCellKey(
                        new DistanceBand(0, 0.0, 5.0),
                        VesselClass.CLASS_A),
                new MetricCounts(90, 10, 100.0, 10.0),
                5, 31, comparisons);
        EnumMap<ValidationMetric, ValidationMetricSummary> summaries =
                new EnumMap<>(ValidationMetric.class);
        for (ValidationMetric metric : ValidationMetric.values()) {
            summaries.put(metric, new ValidationMetricSummary(
                    metric, 0.0, 20.0, 100.0,
                    1, 1, "再現", "再現"));
        }
        ValidationRequest request = new ValidationRequest(
                modelId, LocalDate.of(2025, 12, 1),
                LocalDate.of(2025, 12, 31), Set.of(), 30, 42, false);
        return new ValidationResult(
                SimulationExperimentId.create(), request, definition,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                java.util.stream.LongStream.range(42, 72).boxed().toList(),
                List.of(), List.of(cell), summaries, List.of(),
                "ab".repeat(32));
    }
}
