package ais.aggregate;

import ais.domain.AnalysisProfile;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class MetricCalculator {

    private final AnalysisProfile profile;

    public MetricCalculator(AnalysisProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public MetricEvaluation evaluate(AggregateMetric metric) {
        Objects.requireNonNull(metric, "metric");
        return evaluate(metric.counts(), metric.distinctVesselCount());
    }

    public MetricEvaluation evaluate(MetricCounts counts,
                                     int distinctVesselCount) {
        Objects.requireNonNull(counts, "counts");
        if (distinctVesselCount < 0) {
            throw new IllegalArgumentException(
                    "distinctVesselCount must not be negative");
        }
        long expected = counts.expectedCount();
        Double lossRate = expected == 0
                ? null
                : counts.missingCount() * 100.0 / expected;
        Double freshnessRate = counts.observedSeconds() == 0.0
                ? null
                : counts.staleSeconds()
                * 100.0 / counts.observedSeconds();

        EnumSet<InsufficientDataReason> reasons = EnumSet.noneOf(
                InsufficientDataReason.class);
        if (expected < profile.minimumExpectedCount()) {
            reasons.add(
                    InsufficientDataReason.EXPECTED_COUNT_BELOW_MINIMUM);
        }
        if (distinctVesselCount < profile.minimumDistinctVessels()) {
            reasons.add(
                    InsufficientDataReason.DISTINCT_VESSELS_BELOW_MINIMUM);
        }
        return new MetricEvaluation(
                counts,
                distinctVesselCount,
                lossRate,
                freshnessRate,
                Set.copyOf(reasons));
    }
}
