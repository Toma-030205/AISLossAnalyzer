package ais.analysis;

import ais.domain.GeoPosition;
import ais.spatial.ProjectedPoint;

import java.time.Instant;
import java.util.Objects;

public record EstimatedPosition(
        Instant estimatedAt,
        GeoPosition position,
        ProjectedPoint projectedPoint) {

    public EstimatedPosition {
        Objects.requireNonNull(estimatedAt, "estimatedAt");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(projectedPoint, "projectedPoint");
    }
}
