package ais.aggregate;

import java.util.Objects;
import java.util.Set;

public record AggregateMetric(
        MetricCounts counts,
        Set<Integer> vesselMmsis) {

    public AggregateMetric {
        Objects.requireNonNull(counts, "counts");
        vesselMmsis = Set.copyOf(vesselMmsis);
    }

    public int distinctVesselCount() {
        return vesselMmsis.size();
    }
}
