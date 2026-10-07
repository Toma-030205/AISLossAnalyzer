package ais.simulation.validation;

import ais.aggregate.MetricCounts;

public enum ValidationMetric {
    ESTIMATED_LOSS("推定欠落率") {
        @Override
        public Double ratePercent(MetricCounts counts) {
            long expected = counts.expectedCount();
            return expected == 0 ? null
                    : counts.missingCount() * 100.0 / expected;
        }
    },
    FRESHNESS_VIOLATION("情報鮮度違反率") {
        @Override
        public Double ratePercent(MetricCounts counts) {
            return counts.observedSeconds() == 0.0 ? null
                    : counts.staleSeconds() * 100.0
                    / counts.observedSeconds();
        }
    };

    private final String label;

    ValidationMetric(String label) {
        this.label = label;
    }

    public abstract Double ratePercent(MetricCounts counts);

    @Override
    public String toString() {
        return label;
    }
}
