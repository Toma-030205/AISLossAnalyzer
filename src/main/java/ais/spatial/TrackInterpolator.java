package ais.spatial;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class TrackInterpolator {

    private TrackInterpolator() {
    }

    public static ProjectedPoint interpolate(
            ProjectedPoint start,
            ProjectedPoint end,
            double ratio) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        requireRatio(ratio);
        return new ProjectedPoint(
                start.easting()
                        + ratio * (end.easting() - start.easting()),
                start.northing()
                        + ratio * (end.northing() - start.northing()));
    }

    public static Instant interpolate(
            Instant start,
            Instant end,
            double ratio) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        requireRatio(ratio);
        long durationNanos = Duration.between(start, end).toNanos();
        return start.plusNanos(Math.round(durationNanos * ratio));
    }

    public static double ratio(
            Instant start,
            Instant end,
            Instant sample) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(sample, "sample");
        long totalNanos = Duration.between(start, end).toNanos();
        if (totalNanos <= 0) {
            throw new IllegalArgumentException(
                    "interpolation interval must be positive");
        }
        double result = (double) Duration.between(start, sample).toNanos()
                / totalNanos;
        requireRatio(result);
        return result;
    }

    private static void requireRatio(double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException(
                    "interpolation ratio must be between zero and one");
        }
    }
}
