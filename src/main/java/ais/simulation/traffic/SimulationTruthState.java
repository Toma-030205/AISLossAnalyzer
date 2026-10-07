package ais.simulation.traffic;

import ais.domain.GeoPosition;
import ais.domain.TrailPoint;
import ais.domain.VesselClass;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SimulationTruthState(
        int mmsi,
        VesselClass vesselClass,
        GeoPosition position,
        Double directionDegrees,
        Instant plannedAt,
        List<TrailPoint> trail) {

    public SimulationTruthState {
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(plannedAt, "plannedAt");
        Objects.requireNonNull(trail, "trail");
        trail = List.copyOf(trail);
    }
}
