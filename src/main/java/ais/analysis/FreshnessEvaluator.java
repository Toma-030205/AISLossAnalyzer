package ais.analysis;

import ais.domain.FreshnessState;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class FreshnessEvaluator {

    private final double multiplier;

    public FreshnessEvaluator(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            throw new IllegalArgumentException(
                    "freshness multiplier must be greater than zero");
        }
        this.multiplier = multiplier;
    }

    public FreshnessInterval evaluate(IntervalCandidate interval) {
        Objects.requireNonNull(interval, "interval");
        double limit = interval.expectedIntervalSeconds() * multiplier;
        Instant staleStart = interval.start()
                .receivedAt()
                .plusNanos(Math.round(limit * 1_000_000_000.0));
        double staleSeconds = staleStart.isBefore(
                interval.end().receivedAt())
                ? Duration.between(
                                staleStart,
                                interval.end().receivedAt())
                        .toNanos() / 1_000_000_000.0
                : 0.0;
        return new FreshnessInterval(
                limit,
                staleStart,
                interval.actualSeconds(),
                staleSeconds);
    }

    public FreshnessState state(
            Instant lastReceivedAt,
            double expectedIntervalSeconds,
            Instant displayTime) {
        Objects.requireNonNull(lastReceivedAt, "lastReceivedAt");
        Objects.requireNonNull(displayTime, "displayTime");
        if (!Double.isFinite(expectedIntervalSeconds)
                || expectedIntervalSeconds <= 0.0
                || displayTime.isBefore(lastReceivedAt)) {
            return FreshnessState.UNKNOWN;
        }

        double elapsed = Duration.between(lastReceivedAt, displayTime)
                .toNanos() / 1_000_000_000.0;
        if (elapsed <= expectedIntervalSeconds) {
            return FreshnessState.NORMAL;
        }
        if (elapsed <= expectedIntervalSeconds * multiplier) {
            return FreshnessState.CAUTION;
        }
        return FreshnessState.VIOLATION;
    }
}
