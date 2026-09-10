package ais.storage;

import ais.analysis.AnalysisRunSummary;

import java.util.List;
import java.util.Objects;

public final class AnalysisResultStore {

    private final TransactionRunner transactions;

    public AnalysisResultStore(SqliteDatabase database) {
        this.transactions = new TransactionRunner(
                Objects.requireNonNull(database, "database"));
    }

    public void replaceCompletedRun(
            AnalysisRun run,
            AnalysisRunSummary summary,
            List<DiagnosticSummary> diagnostics,
            List<AnalysisExclusionPeriod> exclusionPeriods) {
        replaceCompletedRun(run, summary, diagnostics, exclusionPeriods,
                List.of());
    }

    public void beginLiveRun(AnalysisRun run) {
        Objects.requireNonNull(run, "run");
        if (run.sourceMode() != ais.domain.SourceMode.LIVE) {
            throw new IllegalArgumentException("a live run is required");
        }
        transactions.execute(connection -> {
            JdbcAnalysisRunRepository.insertPending(connection, run);
            return null;
        });
    }

    public void reopenLiveRun(ais.domain.AnalysisRunId runId) {
        Objects.requireNonNull(runId, "runId");
        transactions.execute(connection -> {
            JdbcAnalysisRunRepository.reopenLive(connection, runId);
            return null;
        });
    }

    public void checkpointLiveRun(
            AnalysisRun run,
            AnalysisRunSummary summary,
            List<DiagnosticSummary> diagnostics,
            List<AnalysisExclusionPeriod> exclusionPeriods,
            List<VesselMetadataObservation> metadataObservations,
            boolean complete) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(summary, "summary");
        if (run.sourceMode() != ais.domain.SourceMode.LIVE) {
            throw new IllegalArgumentException("a live run is required");
        }
        validateIdentity(run, summary);
        List<DiagnosticSummary> diagnosticCopy = List.copyOf(diagnostics);
        List<AnalysisExclusionPeriod> exclusionCopy =
                List.copyOf(exclusionPeriods);
        List<VesselMetadataObservation> metadataCopy =
                List.copyOf(metadataObservations);
        transactions.execute(connection -> {
            JdbcAggregateRepository.replace(connection, run.id(),
                    new AggregateBatch(summary.aggregation()));
            JdbcDiagnosticRepository.replaceSummaries(connection, run.id(),
                    diagnosticCopy);
            JdbcDiagnosticRepository.replaceExclusionPeriods(connection,
                    run.id(), exclusionCopy);
            for (VesselMetadataObservation observation : metadataCopy) {
                JdbcVesselMetadataRepository.saveIfChanged(connection,
                        observation.metadata(), observation.sourceMessageType());
            }
            if (complete) {
                JdbcAnalysisRunRepository.publishCompleted(connection, summary);
            }
            return null;
        });
    }

    public void replaceCompletedRun(
            AnalysisRun run,
            AnalysisRunSummary summary,
            List<DiagnosticSummary> diagnostics,
            List<AnalysisExclusionPeriod> exclusionPeriods,
            List<VesselMetadataObservation> metadataObservations) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(summary, "summary");
        List<DiagnosticSummary> diagnosticCopy =
                List.copyOf(diagnostics);
        List<AnalysisExclusionPeriod> exclusionCopy =
                List.copyOf(exclusionPeriods);
        List<VesselMetadataObservation> metadataCopy =
                List.copyOf(metadataObservations);
        validateIdentity(run, summary);

        transactions.execute(connection -> {
            JdbcAnalysisRunRepository.insertPending(connection, run);
            JdbcAggregateRepository.replace(connection, run.id(),
                    new AggregateBatch(summary.aggregation()));
            JdbcDiagnosticRepository.replaceSummaries(connection, run.id(),
                    diagnosticCopy);
            JdbcDiagnosticRepository.replaceExclusionPeriods(connection,
                    run.id(), exclusionCopy);
            for (VesselMetadataObservation observation : metadataCopy) {
                JdbcVesselMetadataRepository.saveIfChanged(
                        connection, observation.metadata(),
                        observation.sourceMessageType());
            }
            JdbcAnalysisRunRepository.publishCompleted(connection, summary);
            return null;
        });
    }

    private static void validateIdentity(AnalysisRun run,
                                         AnalysisRunSummary summary) {
        if (!run.id().equals(summary.context().runId())
                || run.sourceMode() != summary.context().sourceMode()
                || !run.receiverProfileId().equals(
                        summary.context().receiverProfile().id())
                || !run.analysisProfileId().equals(
                        summary.context().analysisProfile().id())
                || !run.startedAt().equals(summary.context().startedAt())) {
            throw new IllegalArgumentException(
                    "analysis run and summary describe different executions");
        }
    }
}
