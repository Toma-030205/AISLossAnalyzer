package ais.simulation.communication;

import ais.analysis.AnalysisEngine;
import ais.analysis.AnalysisEvent;
import ais.analysis.IntervalExclusionReason;
import ais.domain.VesselClass;
import ais.domain.VesselMetadataUpdate;
import ais.simulation.traffic.IdealTransmission;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SimulationReceptionProcessor {

    private final CommunicationModel communicationModel;
    private final ReceptionContext context;
    private final AnalysisEngine analysisEngine;
    private final ReceivedEventFactory receivedEventFactory;
    private final Map<VesselKey, Boolean> outOfModel = new HashMap<>();
    private long nextSequence;

    public SimulationReceptionProcessor(
            CommunicationModel communicationModel,
            ReceptionContext context,
            AnalysisEngine analysisEngine) {
        this(communicationModel, context, analysisEngine,
                new ReceivedEventFactory(), 0L);
    }

    public SimulationReceptionProcessor(
            CommunicationModel communicationModel,
            ReceptionContext context,
            AnalysisEngine analysisEngine,
            ReceivedEventFactory receivedEventFactory,
            long initialSequence) {
        this.communicationModel = Objects.requireNonNull(
                communicationModel, "communicationModel");
        this.context = Objects.requireNonNull(context, "context");
        this.analysisEngine = Objects.requireNonNull(
                analysisEngine, "analysisEngine");
        this.receivedEventFactory = Objects.requireNonNull(
                receivedEventFactory, "receivedEventFactory");
        if (initialSequence < 0) {
            throw new IllegalArgumentException(
                    "initialSequence must not be negative");
        }
        nextSequence = initialSequence;
    }

    public ReceptionStepResult accept(IdealTransmission transmission) {
        Objects.requireNonNull(transmission, "transmission");
        ReceptionDecision decision = communicationModel.decide(
                transmission, context);
        VesselKey key = new VesselKey(
                transmission.mmsi(), transmission.vesselClass());

        if (decision.outcome() == ReceptionOutcome.OUT_OF_MODEL) {
            if (!outOfModel.getOrDefault(key, false)) {
                analysisEngine.resetVesselContinuity(
                        key.mmsi(),
                        key.vesselClass(),
                        IntervalExclusionReason
                                .SIMULATION_OUT_OF_MODEL_BOUNDARY);
            }
            outOfModel.put(key, true);
            return new ReceptionStepResult(decision, null, java.util.List.of());
        }

        outOfModel.put(key, false);
        if (decision.outcome() == ReceptionOutcome.LOST) {
            return new ReceptionStepResult(decision, null, java.util.List.of());
        }

        var report = receivedEventFactory.create(
                transmission, nextSequence++);
        var analysisEvents = analysisEngine.accept(report);
        return new ReceptionStepResult(
                decision, report, analysisEvents);
    }

    public List<AnalysisEvent> acceptMetadata(
            VesselMetadataUpdate metadataUpdate) {
        return analysisEngine.accept(Objects.requireNonNull(
                metadataUpdate, "metadataUpdate"));
    }

    private record VesselKey(int mmsi, VesselClass vesselClass) {
    }
}
