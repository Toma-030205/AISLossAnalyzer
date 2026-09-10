package ais.storage;

import ais.analysis.AnalysisRunSummary;
import ais.domain.AnalysisProfileId;
import ais.domain.AnalysisRunId;
import ais.domain.ReceiverProfileId;
import ais.input.history.InputFingerprint;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AnalysisRunRepository {

    AnalysisRunId begin(AnalysisRun run);

    void complete(AnalysisRunSummary summary);

    Optional<StoredAnalysisRun> findEquivalent(
            InputFingerprint input,
            ReceiverProfileId receiver,
            AnalysisProfileId profile);

    Optional<StoredAnalysisRun> findEquivalent(
            LocalDate targetDate,
            InputFingerprint input,
            ReceiverProfileId receiver,
            AnalysisProfileId profile);

    Optional<StoredAnalysisRun> findById(AnalysisRunId runId);

    List<StoredAnalysisRun> findCompleted(
            Instant fromInclusive,
            Instant toExclusive,
            ReceiverProfileId receiver,
            AnalysisProfileId profile);
}
