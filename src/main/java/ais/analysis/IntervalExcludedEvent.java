package ais.analysis;

import ais.domain.PositionReport;

import java.time.Instant;
import java.util.Objects;

public record IntervalExcludedEvent(
        PositionReport report,
        IntervalExclusionReason reason) implements AnalysisEvent {

    public IntervalExcludedEvent {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(reason, "reason");
    }

    @Override
    public Instant occurredAt() {
        return report.receivedAt();
    }
}
