package ais.storage;

import ais.aggregate.MetricCounts;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.util.Objects;

public record StoredShipLengthPerformanceRow(
        int shipLengthBandOrder,
        DistanceBand distanceBand,
        VesselClass vesselClass,
        MetricCounts counts,
        int distinctVesselCount,
        int observationDayCount) {

    public StoredShipLengthPerformanceRow {
        if (shipLengthBandOrder < 0) {
            throw new IllegalArgumentException(
                    "shipLengthBandOrder must not be negative");
        }
        Objects.requireNonNull(distanceBand, "distanceBand");
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(counts, "counts");
        if (distinctVesselCount < 0 || observationDayCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }
}
