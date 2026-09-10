package ais.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record VesselDisplayState(
        int mmsi,
        VesselClass vesselClass,
        GeoPosition position,
        Double directionDegrees,
        Double sogKnots,
        Double cogDegrees,
        Double trueHeadingDegrees,
        Integer navigationStatus,
        int messageType,
        FreshnessState freshness,
        Instant receivedAt,
        VesselMetadata metadata,
        List<TrailPoint> trail,
        boolean visible) {

    public VesselDisplayState {
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(freshness, "freshness");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (messageType != 1 && messageType != 2
                && messageType != 3 && messageType != 18) {
            throw new IllegalArgumentException(
                    "display message type must be 1, 2, 3, or 18");
        }
        trail = List.copyOf(trail);
    }
}
