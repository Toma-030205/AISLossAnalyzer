package ais.spatial;

import java.util.Objects;

public final class GridDefinition {

    private final int zone;
    private final double originEasting;
    private final double originNorthing;
    private final int cellSizeMeters;

    public GridDefinition(
            int zone,
            double originEasting,
            double originNorthing,
            int cellSizeMeters) {
        if (zone <= 0) {
            throw new IllegalArgumentException(
                    "UTM zone must be greater than zero");
        }
        if (!Double.isFinite(originEasting)
                || !Double.isFinite(originNorthing)) {
            throw new IllegalArgumentException(
                    "grid origin must be finite");
        }
        if (cellSizeMeters <= 0) {
            throw new IllegalArgumentException(
                    "grid cell size must be greater than zero");
        }
        this.zone = zone;
        this.originEasting = originEasting;
        this.originNorthing = originNorthing;
        this.cellSizeMeters = cellSizeMeters;
    }

    public static GridDefinition utm53N(int cellSizeMeters) {
        return new GridDefinition(
                GridCellId.UTM_ZONE_53_NORTH,
                0.0,
                0.0,
                cellSizeMeters);
    }

    public GridCellId cellFor(ProjectedPoint point) {
        Objects.requireNonNull(point, "point");
        long column = (long) Math.floor(
                (point.easting() - originEasting) / cellSizeMeters);
        long row = (long) Math.floor(
                (point.northing() - originNorthing) / cellSizeMeters);
        return new GridCellId(zone, column, row);
    }

    public double westernBoundary(GridCellId cell) {
        requireZone(cell);
        return originEasting + cell.column() * cellSizeMeters;
    }

    public double southernBoundary(GridCellId cell) {
        requireZone(cell);
        return originNorthing + cell.row() * cellSizeMeters;
    }

    public int zone() {
        return zone;
    }

    public double originEasting() {
        return originEasting;
    }

    public double originNorthing() {
        return originNorthing;
    }

    public int cellSizeMeters() {
        return cellSizeMeters;
    }

    private void requireZone(GridCellId cell) {
        Objects.requireNonNull(cell, "cell");
        if (cell.zone() != zone) {
            throw new IllegalArgumentException(
                    "grid cell belongs to another UTM zone");
        }
    }
}
