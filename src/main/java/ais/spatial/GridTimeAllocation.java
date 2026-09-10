package ais.spatial;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record GridTimeAllocation(
        GridCellId cell,
        Instant startAt,
        Instant endAt) {

    public GridTimeAllocation {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException(
                    "allocation end must be after its start");
        }
    }

    public double seconds() {
        return Duration.between(startAt, endAt).toNanos()
                / 1_000_000_000.0;
    }
}
