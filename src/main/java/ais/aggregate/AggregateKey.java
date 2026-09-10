package ais.aggregate;

import ais.domain.VesselClass;

import java.time.Instant;
import java.util.Objects;

public record AggregateKey<S>(
        Instant bucketStart,
        S spatialKey,
        VesselClass vesselClass) {

    public AggregateKey {
        Objects.requireNonNull(bucketStart, "bucketStart");
        Objects.requireNonNull(spatialKey, "spatialKey");
        Objects.requireNonNull(vesselClass, "vesselClass");
        if (vesselClass == VesselClass.UNKNOWN) {
            throw new IllegalArgumentException(
                    "position metrics require Class A or Class B");
        }
    }
}
