package ais.spatial;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

public final class GridSegmentAllocator {

    private static final double RATIO_EPSILON = 1.0e-12;

    private final GridDefinition grid;

    public GridSegmentAllocator(GridDefinition grid) {
        this.grid = Objects.requireNonNull(grid, "grid");
    }

    public List<GridTimeAllocation> allocate(
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
        addAxisCuts(
                cuts,
                start.easting(),
                end.easting(),
                grid.originEasting());
        addAxisCuts(
                cuts,
                start.northing(),
                end.northing(),
                grid.originNorthing());

        List<Double> ratios = List.copyOf(cuts);
        List<GridTimeAllocation> result = new ArrayList<>();
        for (int index = 0; index < ratios.size() - 1; index++) {
            double first = ratios.get(index);
            double second = ratios.get(index + 1);
            if (second - first <= RATIO_EPSILON) {
                continue;
            }
            double midpoint = (first + second) / 2.0;
            GridCellId cell = grid.cellFor(
                    TrackInterpolator.interpolate(start, end, midpoint));
            Instant allocationStart = TrackInterpolator.interpolate(
                    startAt,
                    endAt,
                    first);
            Instant allocationEnd = TrackInterpolator.interpolate(
                    startAt,
                    endAt,
                    second);
            if (allocationEnd.isAfter(allocationStart)) {
                result.add(new GridTimeAllocation(
                        cell,
                        allocationStart,
                        allocationEnd));
            }
        }
        return List.copyOf(result);
    }

    private void addAxisCuts(
            TreeSet<Double> cuts,
            double start,
            double end,
            double origin) {
        double delta = end - start;
        if (Math.abs(delta) < 1.0e-9) {
            return;
        }

        double minimum = Math.min(start, end);
        double maximum = Math.max(start, end);
        long firstBoundaryIndex = (long) Math.floor(
                (minimum - origin) / grid.cellSizeMeters()) + 1;
        long lastBoundaryIndex = (long) Math.ceil(
                (maximum - origin) / grid.cellSizeMeters()) - 1;

        for (long index = firstBoundaryIndex;
             index <= lastBoundaryIndex;
             index++) {
            double boundary = origin + index * grid.cellSizeMeters();
            double ratio = (boundary - start) / delta;
            if (ratio > RATIO_EPSILON
                    && ratio < 1.0 - RATIO_EPSILON) {
                cuts.add(ratio);
            }
        }
    }
}
