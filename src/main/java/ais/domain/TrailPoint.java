package ais.domain;

import java.time.Instant;
import java.util.Objects;

public record TrailPoint(Instant receivedAt, GeoPosition position) {

    public TrailPoint {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(position, "position");
    }
}
