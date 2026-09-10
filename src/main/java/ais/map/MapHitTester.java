package ais.map;

import ais.domain.GeoPosition;
import ais.spatial.CoordinateProjector;
import ais.spatial.GridCellId;
import ais.spatial.GridDefinition;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class MapHitTester {

    public Optional<Integer> vesselAt(
            double x,
            double y,
            Map<Integer, GeoPosition> vessels,
            MapViewport viewport,
            int width,
            int height,
            double margin,
            double radiusPixels) {
        Objects.requireNonNull(vessels, "vessels");
        Integer nearest = null;
        double bestSquared = radiusPixels * radiusPixels;
        for (Map.Entry<Integer, GeoPosition> entry : vessels.entrySet()) {
            ScreenPoint point = viewport.toScreen(
                    entry.getValue(), width, height, margin);
            double dx = x - point.x();
            double dy = y - point.y();
            double squared = dx * dx + dy * dy;
            if (squared <= bestSquared) {
                nearest = entry.getKey();
                bestSquared = squared;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public Optional<GridCellId> gridCellAt(
            double x,
            double y,
            Set<GridCellId> availableCells,
            MapViewport viewport,
            int width,
            int height,
            double margin,
            CoordinateProjector gridProjector,
            GridDefinition grid) {
        Objects.requireNonNull(availableCells, "availableCells");
        GeoPosition position = viewport.toGeo(
                x, y, width, height, margin);
        GridCellId cell = grid.cellFor(gridProjector.project(position));
        return availableCells.contains(cell)
                ? Optional.of(cell) : Optional.empty();
    }
}
