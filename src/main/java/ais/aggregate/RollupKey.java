package ais.aggregate;

import ais.domain.VesselClass;

import java.util.Objects;

public record RollupKey<S>(
        RollupDimension dimension,
        String periodValue,
        S spatialKey,
        VesselClass vesselClass) {

    public RollupKey {
        Objects.requireNonNull(dimension, "dimension");
        if (periodValue == null || periodValue.isBlank()) {
            throw new IllegalArgumentException(
                    "period value must not be blank");
        }
        Objects.requireNonNull(spatialKey, "spatialKey");
        Objects.requireNonNull(vesselClass, "vesselClass");
    }
}
