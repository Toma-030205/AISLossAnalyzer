package ais.app;

import ais.analysis.AnalysisSnapshot;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.communication.ReceptionDecision;
import ais.simulation.traffic.IdealTransmissionDay;
import ais.simulation.traffic.SimulationTruthState;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record SimulationFrame(
        SimulationPlaybackState state,
        IdealTransmissionDay day,
        CommunicationModelDefinition model,
        long seed,
        Instant startTime,
        Instant endTime,
        Instant displayTime,
        AnalysisSnapshot receivedSnapshot,
        Map<Integer, SimulationTruthState> truthStates,
        Map<Integer, ReceptionDecision> lastDecisions,
        SimulationDiagnostics diagnostics,
        String message) {

    public SimulationFrame {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(truthStates, "truthStates");
        Objects.requireNonNull(lastDecisions, "lastDecisions");
        Objects.requireNonNull(diagnostics, "diagnostics");
        truthStates = Map.copyOf(truthStates);
        lastDecisions = Map.copyOf(lastDecisions);
        message = message == null ? "" : message;
        if (day != null && (model == null || startTime == null
                || endTime == null || displayTime == null
                || receivedSnapshot == null || endTime.isBefore(startTime))) {
            throw new IllegalArgumentException(
                    "loaded simulation frames require complete state");
        }
    }
}
