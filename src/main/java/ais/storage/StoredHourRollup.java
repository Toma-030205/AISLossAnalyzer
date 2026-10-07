package ais.storage;

import ais.aggregate.MetricCounts;
import ais.domain.VesselClass;

import java.util.Objects;

public record StoredHourRollup(
        int hour,
        VesselClass vesselClass,
        MetricCounts counts,
        int distinctVesselCount,
        int observationDayCount) {

    public StoredHourRollup {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("hour must be between 0 and 23");
        }
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(counts, "counts");
        if (distinctVesselCount < 0 || observationDayCount < 0) {
            throw new IllegalArgumentException(
                    "rollup counts must not be negative");
        }
    }
}
