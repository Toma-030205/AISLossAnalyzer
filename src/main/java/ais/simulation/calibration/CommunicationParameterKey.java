package ais.simulation.calibration;

import ais.domain.VesselClass;

import java.util.Objects;

public record CommunicationParameterKey(
        int distanceBandIndex,
        VesselClass vesselClass) {

    public CommunicationParameterKey {
        if (distanceBandIndex < 0) {
            throw new IllegalArgumentException(
                    "distanceBandIndex must not be negative");
        }
        Objects.requireNonNull(vesselClass, "vesselClass");
    }
}
