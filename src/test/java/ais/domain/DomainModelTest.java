package ais.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainModelTest {

    @Test
    void phaseOneDefaultsMatchConfirmedDesign() {
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();

        assertEquals(3.0, profile.freshnessMultiplier());
        assertEquals(2_000, profile.gridSizeMeters());
        assertEquals(0.0, profile.gridOriginEasting());
        assertEquals(0.0, profile.gridOriginNorthing());
        assertEquals(5, profile.distanceBinKilometers());
        assertEquals(70, profile.maximumDistanceKilometers());
        assertEquals(30, profile.minimumExpectedCount());
        assertEquals(3, profile.minimumDistinctVessels());
        assertEquals(Duration.ofMinutes(30), profile.trackGapThreshold());
        assertEquals(30.0, profile.maximumDistanceJumpKilometers());
        assertEquals(30.0, profile.classBCsHighSpeedIntervalSeconds());
    }

    @Test
    void positionReportInfersVesselClassFromMessageType() {
        assertEquals(VesselClass.CLASS_A, position(1).vesselClass());
        assertEquals(VesselClass.CLASS_A, position(2).vesselClass());
        assertEquals(VesselClass.CLASS_A, position(3).vesselClass());
        assertEquals(VesselClass.CLASS_B, position(18).vesselClass());
    }

    @Test
    void rejectsUnsupportedPositionMessageType() {
        assertThrows(IllegalArgumentException.class, () -> position(5));
    }

    @Test
    void metadataNormalizesBlankText() {
        VesselMetadataUpdate metadata = new VesselMetadataUpdate(
                Instant.EPOCH,
                0,
                5,
                123_456_789,
                VesselClass.CLASS_A,
                null,
                "  ",
                " TEST SHIP ",
                null,
                null,
                null);

        assertNull(metadata.callSign());
        assertEquals("TEST SHIP", metadata.vesselName());
    }

    @Test
    void metadataTypeAndVesselClassMustAgree() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VesselMetadataUpdate(
                        Instant.EPOCH,
                        0,
                        5,
                        123_456_789,
                        VesselClass.CLASS_B,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    @Test
    void receiverProfileUsesInclusiveEffectiveDates() {
        ReceiverProfile profile = new ReceiverProfile(
                new ReceiverProfileId("lab"),
                "Laboratory",
                new GeoPosition(34.718983358515715, 135.29057866131427),
                null,
                null,
                null,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                null);

        assertTrue(profile.isEffectiveOn(LocalDate.of(2026, 1, 1)));
        assertTrue(profile.isEffectiveOn(LocalDate.of(2026, 12, 31)));
        assertFalse(profile.isEffectiveOn(LocalDate.of(2027, 1, 1)));
    }

    private static PositionReport position(int type) {
        return new PositionReport(
                Instant.EPOCH,
                0,
                type,
                123_456_789,
                new GeoPosition(34.7, 135.3),
                10.0,
                90.0,
                90.0,
                type == 18 ? null : 0,
                ClassBReportingMode.UNKNOWN,
                false);
    }
}
