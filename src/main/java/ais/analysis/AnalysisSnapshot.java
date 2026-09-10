package ais.analysis;

import ais.aggregate.AggregationSnapshot;
import ais.domain.AnalysisContext;
import ais.domain.VesselDisplayState;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record AnalysisSnapshot(
        AnalysisContext context,
        Instant displayTime,
        AnalysisFilter filter,
        Map<Integer, VesselDisplayState> vessels,
        AggregationSnapshot aggregation,
        long acceptedIntervalCount,
        Map<IntervalExclusionReason, Long> excludedIntervalCounts) {

    public AnalysisSnapshot {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(displayTime, "displayTime");
        Objects.requireNonNull(filter, "filter");
        vessels = Map.copyOf(vessels);
        Objects.requireNonNull(aggregation, "aggregation");
        excludedIntervalCounts = Map.copyOf(excludedIntervalCounts);
        if (acceptedIntervalCount < 0) {
            throw new IllegalArgumentException(
                    "accepted interval count must not be negative");
        }
    }
}
