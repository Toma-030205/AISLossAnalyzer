package ais.storage;

import ais.domain.VesselMetadata;

import java.util.Objects;

public record VesselMetadataObservation(
        VesselMetadata metadata,
        int sourceMessageType) {

    public VesselMetadataObservation {
        Objects.requireNonNull(metadata, "metadata");
        if (sourceMessageType != 5 && sourceMessageType != 24) {
            throw new IllegalArgumentException(
                    "sourceMessageType must be 5 or 24");
        }
    }
}
