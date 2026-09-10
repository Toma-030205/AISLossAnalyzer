package ais.map;

import ais.domain.GeoPosition;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record MapFeature(
        MapFeatureType type,
        String sourceKind,
        List<GeoPosition> points,
        Path source) {

    public MapFeature {
        Objects.requireNonNull(type, "type");
        if (sourceKind == null || sourceKind.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceKind must not be blank");
        }
        points = List.copyOf(points);
        if (points.size() < 2) {
            throw new IllegalArgumentException(
                    "map feature requires at least two points");
        }
        source = Objects.requireNonNull(source, "source")
                .toAbsolutePath().normalize();
    }

    public boolean area() {
        return sourceKind.endsWith("_a");
    }
}
