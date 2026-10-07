package ais.simulation.traffic;

import ais.domain.VesselMetadataUpdate;
import ais.input.history.InputFingerprint;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record IdealTransmissionDay(
        LocalDate date,
        InputFingerprint inputFingerprint,
        List<IdealTransmission> transmissions,
        List<VesselMetadataUpdate> metadataUpdates,
        IdealTransmissionDiagnostics diagnostics) {

    public IdealTransmissionDay {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(inputFingerprint, "inputFingerprint");
        Objects.requireNonNull(transmissions, "transmissions");
        Objects.requireNonNull(metadataUpdates, "metadataUpdates");
        Objects.requireNonNull(diagnostics, "diagnostics");
        transmissions = List.copyOf(transmissions);
        metadataUpdates = List.copyOf(metadataUpdates);
    }
}
