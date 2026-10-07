package ais.storage;

import ais.aggregate.MetricCounts;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.util.Objects;

public record StoredDistanceRollup(
        String periodValue,
        DistanceBand distanceBand,
        VesselClass vesselClass,
        MetricCounts counts,
        int distinctVesselCount,
        int observationDayCount) {

    public StoredDistanceRollup {
        if (periodValue == null || periodValue.isBlank()) {
            throw new IllegalArgumentException(
                    "periodValue must not be blank");
        }
        Objects.requireNonNull(distanceBand, "distanceBand");
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(counts, "counts");
        if (distinctVesselCount < 0 || observationDayCount < 0) {
            throw new IllegalArgumentException(
                    "rollup counts must not be negative");
        }
    }
}
