package ais.storage;

import ais.domain.AnalysisRunId;

import java.util.List;

public interface DiagnosticRepository {

    void saveSummary(AnalysisRunId runId,
                     List<DiagnosticSummary> summaries);

    void saveExclusionPeriods(AnalysisRunId runId,
                              List<AnalysisExclusionPeriod> periods);

    List<DiagnosticSummary> findSummaries(AnalysisRunId runId);

    List<AnalysisExclusionPeriod> findExclusionPeriods(
            AnalysisRunId runId);
}
