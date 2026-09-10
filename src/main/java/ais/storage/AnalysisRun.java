package ais.storage;

import ais.domain.AnalysisProfileId;
import ais.domain.AnalysisRunId;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.input.history.InputFingerprint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record AnalysisRun(
        AnalysisRunId id,
        SourceMode sourceMode,
        LocalDate targetDate,
        String inputName,
        InputFingerprint inputFingerprint,
        ReceiverProfileId receiverProfileId,
        AnalysisProfileId analysisProfileId,
        Instant startedAt) {

    public AnalysisRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceMode, "sourceMode");
        Objects.requireNonNull(receiverProfileId, "receiverProfileId");
        Objects.requireNonNull(analysisProfileId, "analysisProfileId");
        Objects.requireNonNull(startedAt, "startedAt");
        if (inputName == null || inputName.isBlank()) {
            throw new IllegalArgumentException("inputName must not be blank");
        }
        inputName = inputName.trim();
        if (sourceMode == SourceMode.HISTORICAL
                && (targetDate == null || inputFingerprint == null)) {
            throw new IllegalArgumentException(
                    "historical runs require targetDate and inputFingerprint");
        }
        if (sourceMode == SourceMode.LIVE && targetDate != null) {
            throw new IllegalArgumentException(
                    "live runs must not fix a targetDate");
        }
    }
}
