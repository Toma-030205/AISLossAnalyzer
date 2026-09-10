package ais.decode;

import ais.domain.ClassBReportingMode;
import ais.domain.GeoPosition;
import ais.domain.NormalizedAisEvent;
import ais.domain.PositionReport;
import ais.domain.VesselClass;
import ais.domain.VesselMetadataUpdate;
import ais.model.AisMessage;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

public final class LegacyMessageAdapter {

    public static final ZoneId DEFAULT_SOURCE_ZONE =
            ZoneId.of("Asia/Tokyo");

    private final ZoneId sourceZone;

    public LegacyMessageAdapter() {
        this(DEFAULT_SOURCE_ZONE);
    }

    public LegacyMessageAdapter(ZoneId sourceZone) {
        this.sourceZone = Objects.requireNonNull(sourceZone, "sourceZone");
    }

    public Optional<NormalizedAisEvent> adapt(
            AisMessage message,
            long sequence) {
        if (message == null
                || message.timestamp == null
                || sequence < 0
                || !isValidMmsi(message.mmsi)) {
            return Optional.empty();
        }

        Instant receivedAt = message.timestamp
                .atZone(sourceZone)
                .toInstant();

        if (isPositionType(message.messageType)) {
            return adaptPosition(message, receivedAt, sequence);
        }
        if (message.messageType == 5 || message.messageType == 24) {
            return Optional.of(adaptMetadata(message, receivedAt, sequence));
        }
        return Optional.empty();
    }

    private static Optional<NormalizedAisEvent> adaptPosition(
            AisMessage message,
            Instant receivedAt,
            long sequence) {
        if (!isValidPosition(message.lat, message.lon)) {
            return Optional.empty();
        }

        ClassBReportingMode reportingMode =
                classBReportingMode(message);

        return Optional.of(new PositionReport(
                receivedAt,
                sequence,
                message.messageType,
                message.mmsi,
                new GeoPosition(message.lat, message.lon),
                sanitizeNonNegative(message.sog),
                sanitizeDirection(message.cog),
                sanitizeDirection(message.trueHeading),
                sanitizeNavigationStatus(message.navStatus),
                reportingMode,
                Boolean.TRUE.equals(message.assignedMode)));
    }

    private static VesselMetadataUpdate adaptMetadata(
            AisMessage message,
            Instant receivedAt,
            long sequence) {
        VesselClass vesselClass = message.messageType == 5
                ? VesselClass.CLASS_A
                : VesselClass.CLASS_B;

        return new VesselMetadataUpdate(
                receivedAt,
                sequence,
                message.messageType,
                message.mmsi,
                vesselClass,
                message.imo,
                message.callSign,
                message.vesselName,
                message.shipType,
                message.destination,
                message.shipLength);
    }

    private static ClassBReportingMode classBReportingMode(
            AisMessage message) {
        if (message.messageType != 18 || message.classBCsUnit == null) {
            return ClassBReportingMode.UNKNOWN;
        }
        return message.classBCsUnit
                ? ClassBReportingMode.CARRIER_SENSE
                : ClassBReportingMode.SELF_ORGANIZING;
    }

    private static boolean isPositionType(int messageType) {
        return messageType == 1
                || messageType == 2
                || messageType == 3
                || messageType == 18;
    }

    private static boolean isValidMmsi(int mmsi) {
        return mmsi > 0 && mmsi <= 999_999_999;
    }

    private static boolean isValidPosition(Double latitude, Double longitude) {
        return latitude != null
                && longitude != null
                && Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90.0
                && latitude <= 90.0
                && longitude >= -180.0
                && longitude <= 180.0
                && !(latitude == 0.0 && longitude == 0.0);
    }

    private static Double sanitizeNonNegative(Double value) {
        return value != null
                && Double.isFinite(value)
                && value >= 0.0
                ? value
                : null;
    }

    private static Double sanitizeDirection(Double value) {
        return value != null
                && Double.isFinite(value)
                && value >= 0.0
                && value < 360.0
                ? value
                : null;
    }

    private static Integer sanitizeNavigationStatus(Integer value) {
        return value != null && value >= 0 && value <= 15
                ? value
                : null;
    }
}
