package ais.storage;

import java.time.Instant;
import java.util.Objects;

public record AnalysisExclusionPeriod(
        Instant startedAt,
        Instant endedAt,
        String reason,
        long affectedCount) {

    public AnalysisExclusionPeriod {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "exclusion end must not precede start");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
        if (affectedCount < 0) {
            throw new IllegalArgumentException(
                    "affectedCount must not be negative");
        }
    }
}
