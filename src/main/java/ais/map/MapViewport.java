package ais.map;

import ais.domain.GeoPosition;

import java.util.Objects;

public final class MapViewport {

    public static final double MIN_ZOOM = 0.4;
    public static final double MAX_ZOOM = 8.0;

    private final MapProjection projection;
    private final ProjectedMapPoint southWest;
    private final ProjectedMapPoint northEast;
    private double zoom = 1.0;
    private double panX;
    private double panY;
    private long revision;

    public MapViewport(MapDataset dataset, MapProjection projection) {
        Objects.requireNonNull(dataset, "dataset");
        this.projection = Objects.requireNonNull(projection, "projection");
        southWest = projection.project(dataset.southWest());
        northEast = projection.project(dataset.northEast());
    }

    public ScreenPoint toScreen(GeoPosition position, int width, int height,
                                double margin) {
        ProjectedMapPoint point = projection.project(position);
        Transform transform = transform(width, height, margin);
        double baseX = margin
                + (point.x() - southWest.x()) * transform.scale()
                + transform.offsetX();
        double baseY = margin
                + (northEast.y() - point.y()) * transform.scale()
                + transform.offsetY();
        return new ScreenPoint(
                width / 2.0 + (baseX - width / 2.0) * zoom + panX,
                height / 2.0 + (baseY - height / 2.0) * zoom + panY);
    }

    public GeoPosition toGeo(double screenX, double screenY,
                             int width, int height, double margin) {
        Transform transform = transform(width, height, margin);
        double baseX = width / 2.0
                + (screenX - width / 2.0 - panX) / zoom;
        double baseY = height / 2.0
                + (screenY - height / 2.0 - panY) / zoom;
        double projectedX = southWest.x()
                + (baseX - margin - transform.offsetX())
                / transform.scale();
        double projectedY = northEast.y()
                - (baseY - margin - transform.offsetY())
                / transform.scale();
        return projection.unproject(
                new ProjectedMapPoint(projectedX, projectedY));
    }

    public void zoomAt(double factor, double anchorX, double anchorY,
                       int width, int height) {
        double next = Math.max(MIN_ZOOM,
                Math.min(MAX_ZOOM, zoom * factor));
        if (next == zoom) {
            return;
        }
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        double ratio = next / zoom;
        panX = anchorX - centerX
                - (anchorX - centerX - panX) * ratio;
        panY = anchorY - centerY
                - (anchorY - centerY - panY) * ratio;
        zoom = next;
        revision++;
    }

    public void panBy(double deltaX, double deltaY) {
        panX += deltaX;
        panY += deltaY;
        revision++;
    }

    public void reset() {
        zoom = 1.0;
        panX = 0.0;
        panY = 0.0;
        revision++;
    }

    public double zoom() {
        return zoom;
    }

    public long revision() {
        return revision;
    }

    private Transform transform(int width, int height, double margin) {
        double projectedWidth = northEast.x() - southWest.x();
        double projectedHeight = northEast.y() - southWest.y();
        double drawableWidth = Math.max(1.0, width - margin * 2.0);
        double drawableHeight = Math.max(1.0, height - margin * 2.0);
        double scale = Math.min(drawableWidth / projectedWidth,
                drawableHeight / projectedHeight);
        return new Transform(
                scale,
                (drawableWidth - projectedWidth * scale) / 2.0,
                (drawableHeight - projectedHeight * scale) / 2.0);
    }

    private record Transform(double scale, double offsetX, double offsetY) {
    }
}
