package ais.app;

import ais.analysis.AnalysisRunSummary;
import ais.domain.AnalysisRunId;

import java.util.Objects;

public record FullDayAnalysisResult(
        AnalysisRunId runId,
        AnalysisRunSummary summary,
        long inputDiagnosticCount) {

    public FullDayAnalysisResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(summary, "summary");
        if (inputDiagnosticCount < 0) {
            throw new IllegalArgumentException(
                    "diagnostic count must not be negative");
        }
    }
}
