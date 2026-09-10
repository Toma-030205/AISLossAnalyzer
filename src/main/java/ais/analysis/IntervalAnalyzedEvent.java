package ais.analysis;

import java.time.Instant;
import java.util.Objects;

public record IntervalAnalyzedEvent(AnalyzedInterval interval)
        implements AnalysisEvent {

    public IntervalAnalyzedEvent {
        Objects.requireNonNull(interval, "interval");
    }

    @Override
    public Instant occurredAt() {
        return interval.end().receivedAt();
    }
}
