package ais.domain;

import java.time.Instant;
import java.util.Objects;

public record VesselMetadataUpdate(
        Instant receivedAt,
        long sequence,
        int messageType,
        int mmsi,
        VesselClass vesselClass,
        Integer imo,
        String callSign,
        String vesselName,
        Integer shipType,
        String destination,
        Integer shipLengthMeters) implements NormalizedAisEvent {

    public VesselMetadataUpdate {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(vesselClass, "vesselClass");

        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "sequence must be zero or greater: " + sequence);
        }
        if (messageType != 5 && messageType != 24) {
            throw new IllegalArgumentException(
                    "metadata message type must be 5 or 24: "
                            + messageType);
        }
        if (messageType == 5 && vesselClass != VesselClass.CLASS_A) {
            throw new IllegalArgumentException(
                    "Type 5 metadata must belong to Class A");
        }
        if (messageType == 24 && vesselClass != VesselClass.CLASS_B) {
            throw new IllegalArgumentException(
                    "Type 24 metadata must belong to Class B");
        }
        if (mmsi <= 0 || mmsi > 999_999_999) {
            throw new IllegalArgumentException(
                    "mmsi must be between 1 and 999999999: " + mmsi);
        }
        if (shipLengthMeters != null && shipLengthMeters < 0) {
            throw new IllegalArgumentException(
                    "shipLengthMeters must be zero or greater: "
                            + shipLengthMeters);
        }

        callSign = normalizeText(callSign);
        vesselName = normalizeText(vesselName);
        destination = normalizeText(destination);
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
