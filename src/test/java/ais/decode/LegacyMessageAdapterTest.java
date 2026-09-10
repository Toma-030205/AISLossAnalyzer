package ais.decode;

import ais.domain.NormalizedAisEvent;
import ais.domain.PositionReport;
import ais.domain.VesselMetadataUpdate;
import ais.model.AisMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMessageAdapterTest {

    private final LegacyMessageAdapter adapter =
            new LegacyMessageAdapter();

    @Test
    void convertsHistoricalJapanTimeToInstant() {
        AisMessage legacy = positionMessage();

        NormalizedAisEvent event =
                adapter.adapt(legacy, 12).orElseThrow();
        PositionReport report = assertInstanceOf(
                PositionReport.class,
                event);

        assertEquals(Instant.parse("2025-12-31T15:00:00Z"),
                report.receivedAt());
        assertEquals(12, report.sequence());
        assertEquals(legacy.mmsi, report.mmsi());
        assertEquals(legacy.lat, report.position().latitude());
        assertEquals(legacy.lon, report.position().longitude());
    }

    @Test
    void separatesMetadataFromPositionReports() {
        AisMessage legacy = new AisMessage();
        legacy.messageType = 5;
        legacy.mmsi = 123_456_789;
        legacy.timestamp = LocalDateTime.of(2026, 1, 1, 0, 0);
        legacy.vesselName = " TEST SHIP ";

        VesselMetadataUpdate update = assertInstanceOf(
                VesselMetadataUpdate.class,
                adapter.adapt(legacy, 0).orElseThrow());

        assertEquals("TEST SHIP", update.vesselName());
    }

    @Test
    void rejectsZeroPositionAndUnsupportedType() {
        AisMessage zeroPosition = positionMessage();
        zeroPosition.lat = 0.0;
        zeroPosition.lon = 0.0;

        assertTrue(adapter.adapt(zeroPosition, 0).isEmpty());

        AisMessage unsupported = positionMessage();
        unsupported.messageType = 10;

        assertTrue(adapter.adapt(unsupported, 1).isEmpty());
    }

    private static AisMessage positionMessage() {
        AisMessage message = new AisMessage();
        message.messageType = 1;
        message.mmsi = 123_456_789;
        message.timestamp = LocalDateTime.of(2026, 1, 1, 0, 0);
        message.lat = 34.7;
        message.lon = 135.3;
        message.sog = 10.0;
        message.cog = 90.0;
        message.trueHeading = 90.0;
        message.navStatus = 0;
        return message;
    }
}
