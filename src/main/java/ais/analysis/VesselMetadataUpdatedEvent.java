package ais.analysis;

import ais.domain.VesselMetadata;

import java.time.Instant;
import java.util.Objects;

public record VesselMetadataUpdatedEvent(VesselMetadata metadata)
        implements AnalysisEvent {

    public VesselMetadataUpdatedEvent {
        Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public Instant occurredAt() {
        return metadata.updatedAt();
    }
}
