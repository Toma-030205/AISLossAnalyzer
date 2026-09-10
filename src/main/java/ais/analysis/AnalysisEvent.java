package ais.analysis;

import java.time.Instant;

public sealed interface AnalysisEvent
        permits IntervalAnalyzedEvent,
        IntervalExcludedEvent,
        VesselMetadataUpdatedEvent {

    Instant occurredAt();
}
