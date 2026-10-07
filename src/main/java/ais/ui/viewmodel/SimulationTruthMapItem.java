package ais.ui.viewmodel;

import ais.domain.GeoPosition;
import ais.domain.TrailPoint;
import ais.domain.VesselClass;
import ais.simulation.communication.ReceptionOutcome;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SimulationTruthMapItem(
        int mmsi,
        VesselClass vesselClass,
        GeoPosition truthPosition,
        Double directionDegrees,
        Instant plannedAt,
        List<TrailPoint> truthTrail,
        GeoPosition lastReceivedPosition,
        ReceptionOutcome lastOutcome,
        Double receptionProbability,
        boolean selected) {

    public SimulationTruthMapItem {
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(truthPosition, "truthPosition");
        Objects.requireNonNull(plannedAt, "plannedAt");
        Objects.requireNonNull(truthTrail, "truthTrail");
        truthTrail = List.copyOf(truthTrail);
    }
}
