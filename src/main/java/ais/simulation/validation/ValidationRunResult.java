package ais.simulation.validation;

import ais.aggregate.MetricCounts;

import java.time.Instant;
import java.util.Map;

public record ValidationRunResult(
        int iteration,
        long seed,
        ValidationModelVariant variant,
        long outOfModelCount,
        Instant startedAt,
        Instant completedAt,
        Map<ValidationCellKey, MetricCounts> metrics) {

    public ValidationRunResult {
        metrics = Map.copyOf(metrics);
        if (iteration < 0 || outOfModelCount < 0 || variant == null
                || startedAt == null || completedAt == null
                || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("invalid validation run");
        }
    }
}
