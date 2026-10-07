package ais.simulation.communication;

import ais.analysis.AnalysisEvent;
import ais.domain.PositionReport;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ReceptionStepResult(
        ReceptionDecision decision,
        PositionReport receivedReport,
        List<AnalysisEvent> analysisEvents) {

    public ReceptionStepResult {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(analysisEvents, "analysisEvents");
        analysisEvents = List.copyOf(analysisEvents);
        if ((decision.outcome() == ReceptionOutcome.RECEIVED)
                != (receivedReport != null)) {
            throw new IllegalArgumentException(
                    "only RECEIVED decisions may contain a report");
        }
    }

    public Optional<PositionReport> receivedReportOptional() {
        return Optional.ofNullable(receivedReport);
    }
}
