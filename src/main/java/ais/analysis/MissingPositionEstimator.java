package ais.analysis;

import ais.domain.GeoPosition;
import ais.spatial.CoordinateProjector;
import ais.spatial.ProjectedPoint;
import ais.spatial.TrackInterpolator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MissingPositionEstimator {

    private final CoordinateProjector projector;

    public MissingPositionEstimator(CoordinateProjector projector) {
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    public List<EstimatedPosition> estimate(
            IntervalCandidate interval,
            long missingCount) {
        Objects.requireNonNull(interval, "interval");
        if (missingCount < 0) {
            throw new IllegalArgumentException(
                    "missing count must not be negative");
        }
        if (missingCount == 0) {
            return List.of();
        }

        ProjectedPoint start = projector.project(
                interval.start().position());
        ProjectedPoint end = projector.project(
                interval.end().position());
        List<EstimatedPosition> result = new ArrayList<>();

        for (long index = 1; index <= missingCount; index++) {
            double elapsedSeconds =
                    interval.expectedIntervalSeconds() * index;
            Instant estimatedAt = interval.start()
                    .receivedAt()
                    .plusNanos(Math.round(
                            elapsedSeconds * 1_000_000_000.0));
            double ratio = elapsedSeconds / interval.actualSeconds();
            ProjectedPoint projected = TrackInterpolator.interpolate(
                    start,
                    end,
                    ratio);
            GeoPosition position = projector.unproject(projected);
            result.add(new EstimatedPosition(
                    estimatedAt,
                    position,
                    projected));
        }
        return List.copyOf(result);
    }
}
