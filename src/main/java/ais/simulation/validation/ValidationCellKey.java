package ais.simulation.validation;

import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.util.Objects;

public record ValidationCellKey(
        DistanceBand distanceBand,
        VesselClass vesselClass) implements Comparable<ValidationCellKey> {

    public ValidationCellKey {
        Objects.requireNonNull(distanceBand, "distanceBand");
        Objects.requireNonNull(vesselClass, "vesselClass");
        if (vesselClass == VesselClass.UNKNOWN) {
            throw new IllegalArgumentException("Class A or B is required");
        }
    }

    @Override
    public int compareTo(ValidationCellKey other) {
        int band = Integer.compare(
                distanceBand.index(), other.distanceBand.index());
        return band != 0 ? band
                : Integer.compare(vesselClass.ordinal(),
                other.vesselClass.ordinal());
    }
}
