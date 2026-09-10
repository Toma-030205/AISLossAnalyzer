package ais.analysis;

import ais.aggregate.AggregationSnapshot;
import ais.domain.AnalysisContext;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class AnalysisSnapshotFactory {

    public AnalysisSnapshot create(
            AnalysisContext context,
            Instant displayTime,
            AnalysisFilter filter,
            VesselStateStore vesselStates,
            FreshnessEvaluator freshnessEvaluator,
            AggregationSnapshot aggregation,
            long acceptedIntervalCount,
            Map<IntervalExclusionReason, Long> exclusions) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(displayTime, "displayTime");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(vesselStates, "vesselStates");
        Objects.requireNonNull(freshnessEvaluator, "freshnessEvaluator");
        Objects.requireNonNull(aggregation, "aggregation");
        Objects.requireNonNull(exclusions, "exclusions");

        return new AnalysisSnapshot(
                context,
                displayTime,
                filter,
                vesselStates.snapshot(
                        displayTime,
                        filter,
                        freshnessEvaluator),
                aggregation.filterByVesselClasses(
                        filter.vesselClasses()),
                acceptedIntervalCount,
                exclusions);
    }
}
