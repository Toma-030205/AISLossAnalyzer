package ais.storage;

import java.time.Instant;
import java.util.Objects;

public record StoredAnalysisRun(
        AnalysisRun run,
        AnalysisRunStatus status,
        boolean active,
        Instant endedAt,
        long acceptedIntervalCount,
        long estimatedMissingCount,
        long metadataUpdateCount,
        long outsideDistanceRangeCount) {

    public StoredAnalysisRun {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(status, "status");
        if (acceptedIntervalCount < 0 || estimatedMissingCount < 0
                || metadataUpdateCount < 0
                || outsideDistanceRangeCount < 0) {
            throw new IllegalArgumentException(
                    "stored analysis counts must not be negative");
        }
        if (status == AnalysisRunStatus.PENDING && endedAt != null) {
            throw new IllegalArgumentException(
                    "pending runs must not have an end time");
        }
        if (status != AnalysisRunStatus.PENDING && endedAt == null) {
            throw new IllegalArgumentException(
                    "completed runs require an end time");
        }
    }
}
