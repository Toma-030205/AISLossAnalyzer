package ais.analysis;

import ais.aggregate.AggregationSnapshot;
import ais.domain.AnalysisContext;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record AnalysisRunSummary(
        AnalysisContext context,
        Instant endedAt,
        long acceptedIntervalCount,
        long estimatedMissingCount,
        long metadataUpdateCount,
        Map<IntervalExclusionReason, Long> excludedIntervalCounts,
        AggregationSnapshot aggregation) {

    public AnalysisRunSummary {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(endedAt, "endedAt");
        excludedIntervalCounts = Map.copyOf(excludedIntervalCounts);
        Objects.requireNonNull(aggregation, "aggregation");
        if (acceptedIntervalCount < 0
                || estimatedMissingCount < 0
                || metadataUpdateCount < 0) {
            throw new IllegalArgumentException(
                    "analysis summary counts must not be negative");
        }
    }
}
