package ais.analysis;

import ais.domain.PositionReport;

import java.util.Objects;

public record IntervalCandidate(
        PositionReport start,
        PositionReport end,
        double expectedIntervalSeconds,
        double actualSeconds,
        double startDistanceKilometers,
        double endDistanceKilometers) {

    public IntervalCandidate {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!Double.isFinite(expectedIntervalSeconds)
                || expectedIntervalSeconds <= 0.0
                || !Double.isFinite(actualSeconds)
                || actualSeconds < 0.0
                || !Double.isFinite(startDistanceKilometers)
                || !Double.isFinite(endDistanceKilometers)
                || startDistanceKilometers < 0.0
                || endDistanceKilometers < 0.0) {
            throw new IllegalArgumentException(
                    "interval values must be finite and usable");
        }
    }
}
