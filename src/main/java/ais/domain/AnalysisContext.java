package ais.domain;

import java.time.Instant;
import java.util.Objects;

public record AnalysisContext(
        ReceiverProfile receiverProfile,
        AnalysisProfile analysisProfile,
        SourceMode sourceMode,
        AnalysisRunId runId,
        Instant startedAt) {

    public AnalysisContext {
        Objects.requireNonNull(receiverProfile, "receiverProfile");
        Objects.requireNonNull(analysisProfile, "analysisProfile");
        Objects.requireNonNull(sourceMode, "sourceMode");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
