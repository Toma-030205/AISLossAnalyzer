package ais.domain;

import java.time.Instant;
import java.util.Objects;

public record PositionReport(
        Instant receivedAt,
        long sequence,
        int messageType,
        int mmsi,
        GeoPosition position,
        Double sogKnots,
        Double cogDegrees,
        Double trueHeadingDegrees,
        Integer navigationStatus,
        ClassBReportingMode classBReportingMode,
        boolean assignedMode) implements NormalizedAisEvent {

    public PositionReport {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(classBReportingMode, "classBReportingMode");

        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "sequence must be zero or greater: " + sequence);
        }
        if (!isPositionMessageType(messageType)) {
            throw new IllegalArgumentException(
                    "position message type must be 1, 2, 3, or 18: "
                            + messageType);
        }
        if (messageType != 18
                && classBReportingMode != ClassBReportingMode.UNKNOWN) {
            throw new IllegalArgumentException(
                    "Class B reporting mode is only valid for Type 18");
        }
        validateMmsi(mmsi);
        validateFiniteNonNegative(sogKnots, "sogKnots");
        validateDirection(cogDegrees, "cogDegrees");
        validateDirection(trueHeadingDegrees, "trueHeadingDegrees");

        if (navigationStatus != null
                && (navigationStatus < 0 || navigationStatus > 15)) {
            throw new IllegalArgumentException(
                    "navigationStatus must be between 0 and 15: "
                            + navigationStatus);
        }
    }

    @Override
    public VesselClass vesselClass() {
        return messageType == 18
                ? VesselClass.CLASS_B
                : VesselClass.CLASS_A;
    }

    public boolean isClassBCarrierSense() {
        return messageType == 18
                && classBReportingMode
                == ClassBReportingMode.CARRIER_SENSE;
    }

    private static boolean isPositionMessageType(int messageType) {
        return messageType == 1
                || messageType == 2
                || messageType == 3
                || messageType == 18;
    }

    private static void validateMmsi(int mmsi) {
        if (mmsi <= 0 || mmsi > 999_999_999) {
            throw new IllegalArgumentException(
                    "mmsi must be between 1 and 999999999: " + mmsi);
        }
    }

    private static void validateFiniteNonNegative(
            Double value,
            String name) {
        if (value != null
                && (!Double.isFinite(value) || value < 0.0)) {
            throw new IllegalArgumentException(
                    name + " must be finite and zero or greater: " + value);
        }
    }

    private static void validateDirection(Double value, String name) {
        if (value != null
                && (!Double.isFinite(value)
                || value < 0.0
                || value >= 360.0)) {
            throw new IllegalArgumentException(
                    name + " must be finite and between 0 and 360: "
                            + value);
        }
    }
}
