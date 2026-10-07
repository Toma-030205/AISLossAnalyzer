package ais.simulation.traffic;

import ais.analysis.IntervalExclusionReason;

import java.util.Map;
import java.util.Objects;

public record IdealTransmissionDiagnostics(
        long observedAnchorCount,
        long interpolatedCount,
        long acceptedIntervalCount,
        Map<IntervalExclusionReason, Long> excludedIntervalCounts) {

    public IdealTransmissionDiagnostics {
        if (observedAnchorCount < 0 || interpolatedCount < 0
                || acceptedIntervalCount < 0) {
            throw new IllegalArgumentException(
                    "diagnostic counts must not be negative");
        }
        Objects.requireNonNull(excludedIntervalCounts,
                "excludedIntervalCounts");
        excludedIntervalCounts = Map.copyOf(excludedIntervalCounts);
    }
}
