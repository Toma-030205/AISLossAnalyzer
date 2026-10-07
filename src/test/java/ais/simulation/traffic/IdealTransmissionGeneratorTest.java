package ais.simulation.traffic;

import ais.analysis.IntervalExclusionReason;
import ais.domain.AnalysisProfile;
import ais.domain.ClassBReportingMode;
import ais.domain.GeoPosition;
import ais.domain.NormalizedAisEvent;
import ais.domain.PositionReport;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.domain.VesselMetadataUpdate;
import ais.input.history.InputFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdealTransmissionGeneratorTest {

    private static final LocalDate DATE = LocalDate.of(2025, 12, 1);
    private static final Instant START =
            Instant.parse("2025-11-30T15:00:00Z");
    private static final int MMSI = 431_000_001;

    @Test
    void reconstructsMissingTransmissionsWithExistingAnalysisRules() {
        PositionReport first = report(START, 10, 34.68, 135.20, 7);
        PositionReport second = report(
                START.plusSeconds(40), 11, 34.72, 135.24, 8);
        VesselMetadataUpdate metadata = new VesselMetadataUpdate(
                START.plusSeconds(1), 12, 5, MMSI, VesselClass.CLASS_A,
                null, null, "TEST SHIP", null, null, 120);

        IdealTransmissionDay day = new IdealTransmissionGenerator()
                .generate(
                        DATE,
                        fingerprint(),
                        List.of(second, metadata, first),
                        receiver(),
                        AnalysisProfile.phaseOneDefaults());

        assertEquals(5, day.transmissions().size());
        assertEquals(List.of(
                        START,
                        START.plusSeconds(10),
                        START.plusSeconds(20),
                        START.plusSeconds(30),
                        START.plusSeconds(40)),
                day.transmissions().stream()
                        .map(IdealTransmission::plannedAt)
                        .toList());
        assertEquals(List.of(
                        TransmissionOrigin.OBSERVED_ANCHOR,
                        TransmissionOrigin.INTERPOLATED,
                        TransmissionOrigin.INTERPOLATED,
                        TransmissionOrigin.INTERPOLATED,
                        TransmissionOrigin.OBSERVED_ANCHOR),
                day.transmissions().stream()
                        .map(IdealTransmission::origin)
                        .toList());
        IdealTransmission inserted = day.transmissions().get(2);
        assertEquals(first.sogKnots(), inserted.sogKnots());
        assertEquals(first.navigationStatus(), inserted.navigationStatus());
        assertEquals(34.70, inserted.position().latitude(), 2.0e-5);
        assertEquals(135.22, inserted.position().longitude(), 2.0e-5);
        assertEquals(2, day.diagnostics().observedAnchorCount());
        assertEquals(3, day.diagnostics().interpolatedCount());
        assertEquals(1, day.diagnostics().acceptedIntervalCount());
        assertEquals(List.of(metadata), day.metadataUpdates());
    }

    @Test
    void doesNotBridgeExcludedIntervals() {
        PositionReport first = report(START, 0, 34.68, 135.20, 1);
        PositionReport afterLongGap = report(
                START.plusSeconds(1_800), 1, 34.69, 135.21, 1);

        IdealTransmissionDay day = new IdealTransmissionGenerator()
                .generate(
                        DATE,
                        fingerprint(),
                        List.of(first, afterLongGap),
                        receiver(),
                        AnalysisProfile.phaseOneDefaults());

        assertEquals(2, day.transmissions().size());
        assertEquals(0, day.diagnostics().interpolatedCount());
        assertEquals(1L, day.diagnostics().excludedIntervalCounts()
                .get(IntervalExclusionReason.GAP_30_MINUTES_OR_MORE));
    }

    @Test
    void stableIdsDoNotDependOnInputOrder() {
        PositionReport firstA = report(START, 3, 34.68, 135.20, 1);
        PositionReport secondA = report(
                START.plusSeconds(20), 4, 34.69, 135.21, 1);
        PositionReport classB = new PositionReport(
                START.plusSeconds(5), 2, 18, 431_000_002,
                new GeoPosition(34.68, 135.20), 1.0, 90.0, 90.0,
                null, ClassBReportingMode.SELF_ORGANIZING, false);
        IdealTransmissionGenerator generator =
                new IdealTransmissionGenerator();

        IdealTransmissionDay first = generator.generate(
                DATE, fingerprint(), List.of(firstA, classB, secondA),
                receiver(), AnalysisProfile.phaseOneDefaults());
        IdealTransmissionDay second = generator.generate(
                DATE, fingerprint(), List.of(secondA, firstA, classB),
                receiver(), AnalysisProfile.phaseOneDefaults());

        assertEquals(
                first.transmissions().stream().map(IdealTransmission::id)
                        .toList(),
                second.transmissions().stream().map(IdealTransmission::id)
                        .toList());
        assertTrue(first.transmissions().stream()
                .map(IdealTransmission::plannedAt)
                .reduce((left, right) -> {
                    assertTrue(!right.isBefore(left));
                    return right;
                }).isPresent());
    }

    private static PositionReport report(
            Instant at,
            long sequence,
            double latitude,
            double longitude,
            int navigationStatus) {
        return new PositionReport(
                at, sequence, 1, MMSI,
                new GeoPosition(latitude, longitude),
                10.0, 90.0, 90.0, navigationStatus,
                ClassBReportingMode.UNKNOWN, false);
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Receiver",
                new GeoPosition(34.68, 135.20), 30.0,
                null, null, LocalDate.of(2020, 1, 1), null, null);
    }

    private static InputFingerprint fingerprint() {
        return new InputFingerprint("0".repeat(64), 100L, 1);
    }
}
