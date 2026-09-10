package ais.aggregate;

public record MetricCounts(
        long observedCount,
        long missingCount,
        double observedSeconds,
        double staleSeconds) {

    public static final MetricCounts ZERO =
            new MetricCounts(0, 0, 0.0, 0.0);

    public MetricCounts {
        if (observedCount < 0
                || missingCount < 0
                || !Double.isFinite(observedSeconds)
                || observedSeconds < 0.0
                || !Double.isFinite(staleSeconds)
                || staleSeconds < 0.0
                || staleSeconds > observedSeconds + 1.0e-9) {
            throw new IllegalArgumentException("invalid metric counts");
        }
    }

    public long expectedCount() {
        return Math.addExact(observedCount, missingCount);
    }

    public MetricCounts plus(MetricCounts other) {
        return new MetricCounts(
                Math.addExact(observedCount, other.observedCount),
                Math.addExact(missingCount, other.missingCount),
                observedSeconds + other.observedSeconds,
                staleSeconds + other.staleSeconds);
    }
}
