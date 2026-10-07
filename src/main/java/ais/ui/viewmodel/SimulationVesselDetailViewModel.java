package ais.ui.viewmodel;

import ais.domain.GeoPosition;
import ais.domain.VesselClass;
import ais.simulation.communication.ReceptionOutcome;

import java.time.Instant;
import java.util.Objects;

public record SimulationVesselDetailViewModel(
        int mmsi,
        VesselClass vesselClass,
        GeoPosition truthPosition,
        Instant truthTime,
        GeoPosition lastReceivedPosition,
        Long secondsSinceLastReception,
        Double positionDifferenceMeters,
        ReceptionOutcome lastOutcome,
        Double receptionProbability,
        String distanceBandLabel,
        String modelLabel) {

    public SimulationVesselDetailViewModel {
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(truthPosition, "truthPosition");
        Objects.requireNonNull(truthTime, "truthTime");
        Objects.requireNonNull(modelLabel, "modelLabel");
        if (secondsSinceLastReception != null
                && secondsSinceLastReception < 0) {
            throw new IllegalArgumentException(
                    "secondsSinceLastReception must not be negative");
        }
        if (positionDifferenceMeters != null
                && (!Double.isFinite(positionDifferenceMeters)
                || positionDifferenceMeters < 0.0)) {
            throw new IllegalArgumentException(
                    "positionDifferenceMeters must be non-negative");
        }
    }
}
