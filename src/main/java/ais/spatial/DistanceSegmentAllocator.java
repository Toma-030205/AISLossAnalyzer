package ais.spatial;

import ais.domain.GeoPosition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

public final class DistanceSegmentAllocator {

    private static final double RATIO_EPSILON = 1.0e-12;

    private final DistanceBandDefinition bands;
    private final CoordinateProjector projector;
    private final DistanceCalculator distanceCalculator;
    private final GeoPosition receiverPosition;
    private final ProjectedPoint receiverProjected;

    public DistanceSegmentAllocator(
            DistanceBandDefinition bands,
            CoordinateProjector projector,
            DistanceCalculator distanceCalculator,
            GeoPosition receiverPosition) {
        this.bands = Objects.requireNonNull(bands, "bands");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.distanceCalculator = Objects.requireNonNull(
                distanceCalculator,
                "distanceCalculator");
        this.receiverPosition = Objects.requireNonNull(
                receiverPosition,
                "receiverPosition");
        receiverProjected = projector.project(receiverPosition);
    }

    public List<DistanceTimeAllocation> allocate(
            ProjectedPoint start,
            ProjectedPoint end,
            Instant startAt,
            Instant endAt) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException(
                    "segment end time must be after its start");
        }

        TreeSet<Double> cuts = new TreeSet<>();
        cuts.add(0.0);
        cuts.add(1.0);
        for (DistanceBand band : bands.bands()) {
            addCircleCuts(cuts, start, end,
                    band.upperKilometers() * 1_000.0);
        }

        List<Double> ratios = List.copyOf(cuts);
        List<DistanceTimeAllocation> result = new ArrayList<>();
        for (int index = 0; index < ratios.size() - 1; index++) {
            double first = ratios.get(index);
            double second = ratios.get(index + 1);
            if (second - first <= RATIO_EPSILON) {
                continue;
            }

            double midpoint = (first + second) / 2.0;
            GeoPosition midpointPosition = projector.unproject(
                    TrackInterpolator.interpolate(start, end, midpoint));
            double distance = distanceCalculator.distanceKilometers(
                    receiverPosition,
                    midpointPosition);
            bands.bandFor(distance).ifPresent(band -> {
                Instant allocationStart = TrackInterpolator.interpolate(
                        startAt,
                        endAt,
                        first);
                Instant allocationEnd = TrackInterpolator.interpolate(
                        startAt,
                        endAt,
                        second);
                if (allocationEnd.isAfter(allocationStart)) {
                    result.add(new DistanceTimeAllocation(
                            band,
                            allocationStart,
                            allocationEnd));
                }
            });
        }
        return List.copyOf(result);
    }

    private void addCircleCuts(
            TreeSet<Double> cuts,
            ProjectedPoint start,
            ProjectedPoint end,
            double radiusMeters) {
        double dx = end.easting() - start.easting();
        double dy = end.northing() - start.northing();
        double fx = start.easting() - receiverProjected.easting();
        double fy = start.northing() - receiverProjected.northing();
        double a = dx * dx + dy * dy;
        if (a < 1.0e-12) {
            return;
        }
        double b = 2.0 * (fx * dx + fy * dy);
        double c = fx * fx + fy * fy - radiusMeters * radiusMeters;
        double discriminant = b * b - 4.0 * a * c;
        if (discriminant < 0.0) {
            return;
        }

        double squareRoot = Math.sqrt(discriminant);
        addRatio(cuts, (-b - squareRoot) / (2.0 * a));
        addRatio(cuts, (-b + squareRoot) / (2.0 * a));
    }

    private static void addRatio(TreeSet<Double> cuts, double ratio) {
        if (ratio > RATIO_EPSILON
                && ratio < 1.0 - RATIO_EPSILON) {
            cuts.add(ratio);
        }
    }
}
