package ais.simulation.validation;

import ais.aggregate.MetricCounts;

import java.util.Map;
import java.util.Objects;

public record ValidationCellResult(
        ValidationCellKey key,
        MetricCounts observedCounts,
        int distinctVesselCount,
        int observationDayCount,
        Map<ValidationMetric, ValidationMetricComparison> comparisons) {

    public ValidationCellResult {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(observedCounts, "observedCounts");
        comparisons = Map.copyOf(comparisons);
        if (distinctVesselCount < 0 || observationDayCount < 0) {
            throw new IllegalArgumentException("invalid validation cell");
        }
    }

    public ValidationMetricComparison comparison(ValidationMetric metric) {
        return comparisons.get(metric);
    }
}
