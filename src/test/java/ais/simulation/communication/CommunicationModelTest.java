package ais.simulation.communication;

import ais.domain.AnalysisProfile;
import ais.domain.AnalysisProfileId;
import ais.domain.ClassBReportingMode;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.simulation.calibration.CommunicationModelCode;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelId;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.calibration.CommunicationParameter;
import ais.simulation.calibration.ConfidenceInterval;
import ais.simulation.calibration.ParameterApplicability;
import ais.simulation.traffic.IdealTransmission;
import ais.simulation.traffic.IdealTransmissionId;
import ais.simulation.traffic.TransmissionOrigin;
import ais.spatial.DistanceBand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CommunicationModelTest {

    private static final LocalDate DATE = LocalDate.of(2025, 12, 1);
    private static final CommunicationModelId MODEL_ID =
            CommunicationModelId.parse(
                    "00000000-0000-0000-0000-000000000101");

    @Test
    void cmE1UsesDistanceBandClassAndSeventyKilometerBoundary() {
        CommunicationModelSnapshot snapshot = snapshot(List.of(
                parameter(0, VesselClass.CLASS_A, 100, 0,
                        ParameterApplicability.DIRECT, 1.0),
                parameter(1, VesselClass.CLASS_A, 0, 0,
                        ParameterApplicability.OUT_OF_MODEL, null)));
        EmpiricalDistanceClassModel model = new EmpiricalDistanceClassModel(
                snapshot, profile(), (first, second) -> second.longitude(),
                new DeterministicUniformSource());
        ReceptionContext context = context(123L);

        assertEquals(ReceptionOutcome.RECEIVED,
                model.decide(transmission(2.0, 0), context).outcome());
        assertEquals(ReceptionOutcome.OUT_OF_MODEL,
                model.decide(transmission(7.0, 1), context).outcome());
        assertEquals(ReceptionOutcome.OUT_OF_MODEL,
                model.decide(transmission(70.0, 2), context).outcome());
    }

    @Test
    void deterministicUniformDoesNotDependOnEvaluationOrder() {
        CommunicationModelSnapshot snapshot = snapshot(List.of(
                parameter(0, VesselClass.CLASS_A, 60, 40,
                        ParameterApplicability.DIRECT, 0.6)));
        EmpiricalDistanceClassModel model = new EmpiricalDistanceClassModel(
                snapshot, profile(), (first, second) -> 2.0,
                new DeterministicUniformSource());
        IdealTransmission first = transmission(2.0, 0);
        IdealTransmission second = transmission(2.0, 1);
        ReceptionContext context = context(9876L);

        ReceptionDecision firstBefore = model.decide(first, context);
        ReceptionDecision secondBefore = model.decide(second, context);
        ReceptionDecision secondAfter = model.decide(second, context);
        ReceptionDecision firstAfter = model.decide(first, context);

        assertEquals(firstBefore, firstAfter);
        assertEquals(secondBefore, secondAfter);
        assertNotEquals(firstBefore.uniformValue(),
                secondBefore.uniformValue());
    }

    @Test
    void baselinePoolsClassCountsAndUsesTheSameUniformValue() {
        CommunicationModelSnapshot snapshot = snapshot(List.of(
                parameter(0, VesselClass.CLASS_A, 80, 20,
                        ParameterApplicability.DIRECT, 0.8),
                parameter(1, VesselClass.CLASS_A, 20, 80,
                        ParameterApplicability.DIRECT, 0.2)));
        EmpiricalDistanceClassModel empirical =
                new EmpiricalDistanceClassModel(
                        snapshot, profile(), (first, second) -> 2.0,
                        new DeterministicUniformSource());
        ClassOnlyBaselineModel baseline = new ClassOnlyBaselineModel(
                snapshot, profile(), (first, second) -> 2.0,
                new DeterministicUniformSource());
        IdealTransmission transmission = transmission(2.0, 0);
        ReceptionContext context = context(42L);

        ReceptionDecision empiricalDecision = empirical.decide(
                transmission, context);
        ReceptionDecision baselineDecision = baseline.decide(
                transmission, context);

        assertEquals(empiricalDecision.uniformValue(),
                baselineDecision.uniformValue());
        assertEquals((100.0 + 0.5) / (200.0 + 1.0),
                baselineDecision.appliedProbability(), 1.0e-12);
    }

    @Test
    void receivedEventCopiesTransmissionAndAssignsSequence() {
        IdealTransmission transmission = transmission(2.0, 0);

        var report = new ReceivedEventFactory().create(transmission, 17L);

        assertEquals(17L, report.sequence());
        assertEquals(transmission.plannedAt(), report.receivedAt());
        assertEquals(transmission.position(), report.position());
        assertEquals(transmission.messageType(), report.messageType());
        assertEquals(transmission.sogKnots(), report.sogKnots());
    }

    private static CommunicationModelSnapshot snapshot(
            List<CommunicationParameter> parameters) {
        CommunicationModelDefinition definition =
                new CommunicationModelDefinition(
                        MODEL_ID, CommunicationModelCode.CM_E1, 1, "model",
                        new ReceiverProfileId("receiver"),
                        profile().id(),
                        LocalDate.of(2025, 11, 1),
                        LocalDate.of(2025, 11, 30),
                        "cm-e1-v1", 1_000, 42L,
                        Instant.parse("2026-10-06T00:00:00Z"), null);
        return new CommunicationModelSnapshot(
                definition, Map.of(), List.of(), parameters);
    }

    private static CommunicationParameter parameter(
            int bandIndex,
            VesselClass vesselClass,
            long observed,
            long missing,
            ParameterApplicability applicability,
            Double probability) {
        return new CommunicationParameter(
                new DistanceBand(
                        bandIndex, bandIndex * 5.0,
                        (bandIndex + 1.0) * 5.0),
                vesselClass, observed, missing, 3, 5,
                probability == null ? null : 1.0 - probability,
                probability,
                probability,
                new ConfidenceInterval(0.0, 1.0),
                applicability,
                probability,
                null,
                null);
    }

    private static IdealTransmission transmission(
            double distanceMarker,
            int ordinal) {
        Instant at = Instant.parse("2025-11-30T15:00:00Z")
                .plusSeconds(ordinal);
        IdealTransmissionId id = new IdealTransmissionId(
                DATE, 431_000_001, VesselClass.CLASS_A, at, ordinal);
        return new IdealTransmission(
                id, 1, new GeoPosition(0.0, distanceMarker),
                10.0, 90.0, 90.0, 0,
                ClassBReportingMode.UNKNOWN, false, false,
                TransmissionOrigin.OBSERVED_ANCHOR);
    }

    private static ReceptionContext context(long seed) {
        return new ReceptionContext(receiver(), MODEL_ID, seed);
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Receiver",
                new GeoPosition(0.0, 0.0), 30.0,
                null, null, LocalDate.of(2020, 1, 1), null, null);
    }

    private static AnalysisProfile profile() {
        AnalysisProfile defaults = AnalysisProfile.phaseOneDefaults();
        return new AnalysisProfile(
                new AnalysisProfileId("phase-1-default"),
                defaults.rulesVersion(), defaults.freshnessMultiplier(),
                defaults.gridSizeMeters(), defaults.gridOriginEasting(),
                defaults.gridOriginNorthing(),
                defaults.distanceBinKilometers(),
                defaults.maximumDistanceKilometers(),
                defaults.minimumExpectedCount(),
                defaults.minimumDistinctVessels(),
                defaults.trackGapThreshold(),
                defaults.maximumDistanceJumpKilometers(),
                defaults.classBCsHighSpeedIntervalSeconds());
    }
}
