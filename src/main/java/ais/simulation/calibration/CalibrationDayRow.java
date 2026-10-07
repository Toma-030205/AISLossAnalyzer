package ais.simulation.calibration;

import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record CalibrationDayRow(
        LocalDate date,
        DistanceBand distanceBand,
        VesselClass vesselClass,
        long observedCount,
        long missingCount,
        Set<Integer> mmsis) {

    public CalibrationDayRow {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(distanceBand, "distanceBand");
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(mmsis, "mmsis");
        if (observedCount < 0 || missingCount < 0) {
            throw new IllegalArgumentException(
                    "calibration counts must not be negative");
        }
        mmsis = Set.copyOf(mmsis);
    }

    public long expectedCount() {
        return Math.addExact(observedCount, missingCount);
    }
}
