package ais.decode;

import ais.domain.ClassBReportingMode;
import ais.domain.PositionReport;
import ais.domain.VesselClass;
import ais.domain.VesselMetadataUpdate;
import ais.input.SourceReference;
import ais.nmea.CompletedAisPayload;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AisPayloadDecoderTest {

    private static final Instant RECEIVED_AT =
            Instant.parse("2026-09-04T01:02:03Z");
    private static final int MMSI = 431_234_567;

    private final AisPayloadDecoder decoder = new AisPayloadDecoder();

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void decodesClassAPositionReports(int messageType) throws Exception {
        String payload = AisTestData.type1(
                messageType,
                MMSI,
                34.654321,
                135.234567);

        PositionReport report = (PositionReport) decode(
                payload,
                0).event();

        assertEquals(messageType, report.messageType());
        assertEquals(MMSI, report.mmsi());
        assertEquals(VesselClass.CLASS_A, report.vesselClass());
        assertEquals(34.654321, report.position().latitude(), 0.000001);
        assertEquals(135.234567, report.position().longitude(), 0.000001);
        assertEquals(12.3, report.sogKnots());
        assertEquals(90.5, report.cogDegrees());
        assertEquals(91.0, report.trueHeadingDegrees());
        assertEquals(0, report.navigationStatus());
    }

    @Test
    void decodesClassBPositionReport() throws Exception {
        PositionReport report = (PositionReport) decode(
                AisTestData.type18(MMSI, 34.6, 135.3),
                0).event();

        assertEquals(18, report.messageType());
        assertEquals(VesselClass.CLASS_B, report.vesselClass());
        assertEquals(8.7, report.sogKnots());
        assertEquals(180.2, report.cogDegrees());
        assertEquals(180.0, report.trueHeadingDegrees());
        assertEquals(ClassBReportingMode.CARRIER_SENSE,
                report.classBReportingMode());
        assertTrue(report.assignedMode());
        assertNull(report.navigationStatus());
    }

    @Test
    void decodesType5MetadataIncludingLongTextFields() throws Exception {
        AisTestData.PayloadBuilder builder = AisTestData.payload(424)
                .unsigned(0, 6, 5)
                .unsigned(8, 30, MMSI)
                .unsigned(40, 30, 9_876_543)
                .text(70, 42, "JPKOBE")
                .text(112, 120, "OSAKA MARU")
                .unsigned(232, 8, 70)
                .unsigned(240, 9, 80)
                .unsigned(249, 9, 20)
                .text(302, 120, "KOBE");

        VesselMetadataUpdate metadata = (VesselMetadataUpdate) decode(
                builder.armored(),
                builder.fillBits()).event();

        assertEquals(5, metadata.messageType());
        assertEquals(VesselClass.CLASS_A, metadata.vesselClass());
        assertEquals(9_876_543, metadata.imo());
        assertEquals("JPKOBE", metadata.callSign());
        assertEquals("OSAKA MARU", metadata.vesselName());
        assertEquals(70, metadata.shipType());
        assertEquals("KOBE", metadata.destination());
        assertEquals(100, metadata.shipLengthMeters());
    }

    @Test
    void decodesBothType24Parts() throws Exception {
        AisTestData.PayloadBuilder partA = AisTestData.payload(160)
                .unsigned(0, 6, 24)
                .unsigned(8, 30, MMSI)
                .unsigned(38, 2, 0)
                .text(40, 120, "BAY RUNNER");
        AisTestData.PayloadBuilder partB = AisTestData.payload(168)
                .unsigned(0, 6, 24)
                .unsigned(8, 30, MMSI)
                .unsigned(38, 2, 1)
                .unsigned(40, 8, 60)
                .text(90, 42, "JP1234")
                .unsigned(132, 9, 15)
                .unsigned(141, 9, 5);

        VesselMetadataUpdate name = (VesselMetadataUpdate) decode(
                partA.armored(),
                partA.fillBits()).event();
        VesselMetadataUpdate details = (VesselMetadataUpdate) decode(
                partB.armored(),
                partB.fillBits()).event();

        assertEquals("BAY RUNNER", name.vesselName());
        assertEquals("JP1234", details.callSign());
        assertEquals(60, details.shipType());
        assertEquals(20, details.shipLengthMeters());
        assertEquals(VesselClass.CLASS_B, details.vesselClass());
    }

    private DecodedAisEnvelope decode(String payload, int fillBits)
            throws AisDecodeException {
        return decoder.decode(new CompletedAisPayload(
                RECEIVED_AT,
                7,
                SourceReference.live("test", 1),
                "!AIVDM",
                "A",
                payload,
                fillBits));
    }
}
