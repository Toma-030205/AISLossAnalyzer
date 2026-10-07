package ais.simulation.validation;

import ais.aggregate.MetricCounts;

import java.util.List;
import java.util.Objects;

public record ObservedValidationCell(
        ValidationCellKey key,
        MetricCounts counts,
        int distinctVesselCount,
        int observationDayCount,
        List<Double> dailyLossRates,
        List<Double> dailyFreshnessRates) {

    public ObservedValidationCell {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(counts, "counts");
        dailyLossRates = List.copyOf(dailyLossRates);
        dailyFreshnessRates = List.copyOf(dailyFreshnessRates);
        if (distinctVesselCount < 0 || observationDayCount < 0) {
            throw new IllegalArgumentException("invalid observed counts");
        }
    }

    public Double rate(ValidationMetric metric) {
        return metric.ratePercent(counts);
    }

    public StatisticalSummary dailyRange(ValidationMetric metric) {
        return StatisticalSummary.of(metric == ValidationMetric.ESTIMATED_LOSS
                ? dailyLossRates : dailyFreshnessRates);
    }
}
