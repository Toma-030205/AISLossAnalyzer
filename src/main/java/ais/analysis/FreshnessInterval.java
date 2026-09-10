package ais.analysis;

import java.time.Instant;
import java.util.Objects;

public record FreshnessInterval(
        double freshnessLimitSeconds,
        Instant staleStart,
        double observedSeconds,
        double staleSeconds) {

    public FreshnessInterval {
        Objects.requireNonNull(staleStart, "staleStart");
        if (!Double.isFinite(freshnessLimitSeconds)
                || freshnessLimitSeconds <= 0.0
                || !Double.isFinite(observedSeconds)
                || observedSeconds < 0.0
                || !Double.isFinite(staleSeconds)
                || staleSeconds < 0.0
                || staleSeconds > observedSeconds) {
            throw new IllegalArgumentException("invalid freshness interval");
        }
    }
}
