package ais.simulation.communication;

import ais.analysis.DefaultAnalysisEngine;
import ais.analysis.IntervalAnalyzedEvent;
import ais.analysis.IntervalExcludedEvent;
import ais.analysis.IntervalExclusionReason;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.ClassBReportingMode;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.domain.VesselClass;
import ais.simulation.calibration.CommunicationModelId;
import ais.simulation.calibration.ParameterApplicability;
import ais.simulation.traffic.IdealTransmission;
import ais.simulation.traffic.IdealTransmissionId;
import ais.simulation.traffic.TransmissionOrigin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SimulationReceptionProcessorTest {

    private static final Instant START =
            Instant.parse("2025-11-30T15:00:00Z");
    private static final CommunicationModelId MODEL_ID =
            CommunicationModelId.parse(
                    "00000000-0000-0000-0000-000000000201");

    @Test
    void outOfModelBoundaryResetsOnlyTheAffectedVesselOnce() {
        DefaultAnalysisEngine engine = startedEngine();
        CommunicationModel model = (transmission, context) -> {
            if (transmission.position().longitude() == 70.0) {
                return ReceptionDecision.outOfModel(null, 0.25, 70.0);
            }
            return new ReceptionDecision(
                    ReceptionOutcome.RECEIVED, 1.0, null,
                    ParameterApplicability.DIRECT, 0.25, 1.0);
        };
        SimulationReceptionProcessor processor =
                new SimulationReceptionProcessor(
                        model,
                        new ReceptionContext(receiver(), MODEL_ID, 1L),
                        engine);

        assertInstanceOf(IntervalExcludedEvent.class,
                processor.accept(transmission(
                        431_000_001, VesselClass.CLASS_A, START, 0.0))
                        .analysisEvents().getFirst());
        assertInstanceOf(IntervalExcludedEvent.class,
                processor.accept(transmission(
                        431_000_002, VesselClass.CLASS_A, START, 0.0))
                        .analysisEvents().getFirst());
        processor.accept(transmission(
                431_000_001, VesselClass.CLASS_A,
                START.plusSeconds(10), 70.0));
        processor.accept(transmission(
                431_000_001, VesselClass.CLASS_A,
                START.plusSeconds(20), 70.0));

        assertInstanceOf(IntervalAnalyzedEvent.class,
                processor.accept(transmission(
                        431_000_002, VesselClass.CLASS_A,
                        START.plusSeconds(10), 0.0))
                        .analysisEvents().getFirst());
        IntervalExcludedEvent afterBoundary = assertInstanceOf(
                IntervalExcludedEvent.class,
                processor.accept(transmission(
                        431_000_001, VesselClass.CLASS_A,
                        START.plusSeconds(30), 0.0))
                        .analysisEvents().getFirst());

        assertEquals(
                IntervalExclusionReason.SIMULATION_OUT_OF_MODEL_BOUNDARY,
                afterBoundary.reason());
        assertEquals(1L, engine.checkpoint(START.plusSeconds(30))
                .excludedIntervalCounts()
                .get(IntervalExclusionReason
                        .SIMULATION_OUT_OF_MODEL_BOUNDARY));
    }

    @Test
    void receivedSequenceIsIdenticalForTheSameSeedAndInput() {
        DeterministicUniformSource source = new DeterministicUniformSource();
        CommunicationModel model = (transmission, context) -> {
            double uniform = source.value(
                    context.seed(), context.modelId(), transmission.id());
            return new ReceptionDecision(
                    uniform < 0.55
                            ? ReceptionOutcome.RECEIVED
                            : ReceptionOutcome.LOST,
                    0.55, null, ParameterApplicability.DIRECT,
                    uniform, 1.0);
        };
        var first = new SimulationReceptionProcessor(
                model, new ReceptionContext(receiver(), MODEL_ID, 99L),
                startedEngine());
        var second = new SimulationReceptionProcessor(
                model, new ReceptionContext(receiver(), MODEL_ID, 99L),
                startedEngine());

        java.util.List<ais.domain.PositionReport> firstReports =
                new java.util.ArrayList<>();
        java.util.List<ais.domain.PositionReport> secondReports =
                new java.util.ArrayList<>();
        for (int index = 0; index < 20; index++) {
            IdealTransmission transmission = transmission(
                    431_000_001,
                    VesselClass.CLASS_A,
                    START.plusSeconds(index * 10L),
                    0.0);
            first.accept(transmission).receivedReportOptional()
                    .ifPresent(firstReports::add);
            second.accept(transmission).receivedReportOptional()
                    .ifPresent(secondReports::add);
        }

        assertEquals(firstReports, secondReports);
    }

    private static IdealTransmission transmission(
            int mmsi,
            VesselClass vesselClass,
            Instant at,
            double longitudeMarker) {
        int messageType = vesselClass == VesselClass.CLASS_A ? 1 : 18;
        var mode = vesselClass == VesselClass.CLASS_A
                ? ClassBReportingMode.UNKNOWN
                : ClassBReportingMode.SELF_ORGANIZING;
        int ordinal = Math.toIntExact(
                java.time.Duration.between(START, at).toSeconds());
        return new IdealTransmission(
                new IdealTransmissionId(
                        LocalDate.of(2025, 12, 1), mmsi,
                        vesselClass, at, ordinal),
                messageType,
                new GeoPosition(34.68, longitudeMarker),
                10.0, 90.0, 90.0, 0, mode,
                false, false, TransmissionOrigin.OBSERVED_ANCHOR);
    }

    private static DefaultAnalysisEngine startedEngine() {
        DefaultAnalysisEngine engine = new DefaultAnalysisEngine();
        engine.begin(new AnalysisContext(
                receiver(), AnalysisProfile.phaseOneDefaults(),
                SourceMode.HISTORICAL, AnalysisRunId.create(), START));
        return engine;
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Receiver",
                new GeoPosition(34.68, 135.20), 30.0,
                null, null, LocalDate.of(2020, 1, 1), null, null);
    }
}
