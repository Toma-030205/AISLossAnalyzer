package ais.domain;

import java.time.Instant;
import java.util.Objects;

public record VesselMetadata(
        int mmsi,
        VesselClass vesselClass,
        Integer imo,
        String callSign,
        String vesselName,
        Integer shipType,
        String destination,
        Integer shipLengthMeters,
        Instant updatedAt) {

    public VesselMetadata {
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (mmsi <= 0 || mmsi > 999_999_999) {
            throw new IllegalArgumentException("invalid MMSI: " + mmsi);
        }
        callSign = normalize(callSign);
        vesselName = normalize(vesselName);
        destination = normalize(destination);
    }

    public static VesselMetadata from(VesselMetadataUpdate update) {
        Objects.requireNonNull(update, "update");
        return new VesselMetadata(
                update.mmsi(),
                update.vesselClass(),
                update.imo(),
                update.callSign(),
                update.vesselName(),
                update.shipType(),
                update.destination(),
                update.shipLengthMeters(),
                update.receivedAt());
    }

    public VesselMetadata merge(VesselMetadataUpdate update) {
        Objects.requireNonNull(update, "update");
        if (update.mmsi() != mmsi) {
            throw new IllegalArgumentException(
                    "cannot merge metadata from another MMSI");
        }
        return new VesselMetadata(
                mmsi,
                update.vesselClass() == VesselClass.UNKNOWN
                        ? vesselClass
                        : update.vesselClass(),
                prefer(update.imo(), imo),
                prefer(update.callSign(), callSign),
                prefer(update.vesselName(), vesselName),
                prefer(update.shipType(), shipType),
                prefer(update.destination(), destination),
                prefer(update.shipLengthMeters(), shipLengthMeters),
                update.receivedAt().isAfter(updatedAt)
                        ? update.receivedAt()
                        : updatedAt);
    }

    private static <T> T prefer(T newer, T existing) {
        return newer == null ? existing : newer;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
