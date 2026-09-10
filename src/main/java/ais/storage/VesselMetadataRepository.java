package ais.storage;

import ais.domain.VesselMetadata;

import java.time.Instant;
import java.util.Optional;

public interface VesselMetadataRepository {

    boolean saveIfChanged(VesselMetadata metadata, int sourceMessageType);

    Optional<VesselMetadata> findEffectiveAt(int mmsi, Instant time);

    Optional<VesselMetadata> findLatest(int mmsi);
}
