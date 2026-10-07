package ais.ui.viewmodel;

import ais.app.SimulationFrame;
import ais.domain.GeoPosition;
import ais.domain.VesselDisplayState;
import ais.simulation.communication.ReceptionDecision;
import ais.simulation.traffic.SimulationTruthState;
import ais.spatial.HaversineDistanceCalculator;

import java.time.Duration;
import java.util.Comparator;

public final class SimulationOverlayViewModelFactory {

    private final HaversineDistanceCalculator distances =
            new HaversineDistanceCalculator();

    public SimulationOverlayViewModel create(
            SimulationFrame frame,
            Integer selectedMmsi) {
        String modelLabel = frame.model().modelCode().label()
                + " v" + frame.model().revision();
        var truthItems = frame.truthStates().values().stream()
                .map(truth -> item(frame, truth, selectedMmsi))
                .sorted(Comparator.comparingInt(
                        SimulationTruthMapItem::mmsi))
                .toList();
        SimulationTruthState selectedTruth = selectedMmsi == null
                ? null : frame.truthStates().get(selectedMmsi);
        return new SimulationOverlayViewModel(
                truthItems,
                selectedTruth == null ? null
                        : detail(frame, selectedTruth, modelLabel),
                modelLabel,
                frame.seed());
    }

    private static SimulationTruthMapItem item(
            SimulationFrame frame,
            SimulationTruthState truth,
            Integer selectedMmsi) {
        VesselDisplayState received = frame.receivedSnapshot().vessels()
                .get(truth.mmsi());
        ReceptionDecision decision = frame.lastDecisions()
                .get(truth.mmsi());
        return new SimulationTruthMapItem(
                truth.mmsi(), truth.vesselClass(), truth.position(),
                truth.directionDegrees(), truth.plannedAt(),
                selectedMmsi != null && selectedMmsi == truth.mmsi()
                        ? truth.trail() : java.util.List.of(),
                received == null ? null : received.position(),
                decision == null ? null : decision.outcome(),
                decision == null ? null : decision.appliedProbability(),
                selectedMmsi != null && selectedMmsi == truth.mmsi());
    }

    private SimulationVesselDetailViewModel detail(
            SimulationFrame frame,
            SimulationTruthState truth,
            String modelLabel) {
        VesselDisplayState received = frame.receivedSnapshot().vessels()
                .get(truth.mmsi());
        ReceptionDecision decision = frame.lastDecisions()
                .get(truth.mmsi());
        GeoPosition receivedPosition = received == null
                ? null : received.position();
        Long ageSeconds = received == null ? null
                : Math.max(0L, Duration.between(
                        received.receivedAt(), frame.displayTime()).toSeconds());
        Double differenceMeters = receivedPosition == null ? null
                : distances.distanceKilometers(
                        truth.position(), receivedPosition) * 1_000.0;
        String distanceBand = decision == null ? null
                : decision.parameterKey() == null
                ? "適用外"
                : bandLabel(
                        decision.parameterKey().distanceBandIndex(),
                        frame.receivedSnapshot().context()
                                .analysisProfile().distanceBinKilometers());
        return new SimulationVesselDetailViewModel(
                truth.mmsi(), truth.vesselClass(), truth.position(),
                truth.plannedAt(), receivedPosition, ageSeconds,
                differenceMeters,
                decision == null ? null : decision.outcome(),
                decision == null ? null : decision.appliedProbability(),
                distanceBand,
                modelLabel);
    }

    private static String bandLabel(int index, int widthKilometers) {
        int lower = index * widthKilometers;
        return lower + "-" + (lower + widthKilometers) + " km";
    }
}
