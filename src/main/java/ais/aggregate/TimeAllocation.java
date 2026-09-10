package ais.aggregate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record TimeAllocation(
        Instant bucketStart,
        Instant startAt,
        Instant endAt) {

    public TimeAllocation {
        Objects.requireNonNull(bucketStart, "bucketStart");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException(
                    "time allocation end must be after its start");
        }
    }

    public double seconds() {
        return Duration.between(startAt, endAt).toNanos()
                / 1_000_000_000.0;
    }
}
