package ais.simulation.validation;

import ais.aggregate.MetricCounts;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationComparatorTest {

    @Test
    void calculatesWeightedMaeImprovementAndCellStatus() {
        ValidationCellKey key = new ValidationCellKey(
                new DistanceBand(0, 0.0, 5.0), VesselClass.CLASS_A);
        ObservedValidationCell observedCell = new ObservedValidationCell(
                key, new MetricCounts(90, 10, 100.0, 10.0),
                8, 3, List.of(8.0, 10.0, 12.0),
                List.of(8.0, 10.0, 12.0));
        ObservedValidationDataset observed = new ObservedValidationDataset(
                List.of(), Map.of(key, observedCell));
        List<ValidationRunResult> runs = List.of(
                run(0, ValidationModelVariant.CM_E1, key,
                        new MetricCounts(91, 9, 100.0, 9.0)),
                run(1, ValidationModelVariant.CM_E1, key,
                        new MetricCounts(89, 11, 100.0, 11.0)),
                run(0, ValidationModelVariant.CLASS_ONLY_BASELINE, key,
                        new MetricCounts(70, 30, 100.0, 30.0)),
                run(1, ValidationModelVariant.CLASS_ONLY_BASELINE, key,
                        new MetricCounts(70, 30, 100.0, 30.0)));

        ValidationComparator.Comparison result =
                new ValidationComparator().compare(observed, runs);

        ValidationMetricComparison loss = result.cells().getFirst()
                .comparison(ValidationMetric.ESTIMATED_LOSS);
        assertEquals(10.0, loss.simulation().mean(), 1.0e-9);
        assertEquals(30.0, loss.baseline().mean(), 1.0e-9);
        assertEquals(ValidationCellStatus.MATCH, loss.status());
        ValidationMetricSummary summary = result.summaries().get(
                ValidationMetric.ESTIMATED_LOSS);
        assertEquals(0.0, summary.modelWeightedMaePoints(), 1.0e-9);
        assertEquals(20.0, summary.baselineWeightedMaePoints(), 1.0e-9);
        assertEquals(100.0, summary.improvementPercent(), 1.0e-9);
        assertEquals(1, summary.withinObservedVariationCount());
        assertTrue(summary.comparableCellCount() > 0);
    }

    private static ValidationRunResult run(
            int iteration,
            ValidationModelVariant variant,
            ValidationCellKey key,
            MetricCounts counts) {
        Instant start = Instant.parse("2025-12-01T00:00:00Z");
        return new ValidationRunResult(
                iteration, 42L + iteration, variant, 0,
                start, start.plusSeconds(1), Map.of(key, counts));
    }
}
