package ais.map;

import ais.domain.GeoPosition;

import java.util.List;

public record MapDataset(
        List<MapFeature> features,
        GeoPosition southWest,
        GeoPosition northEast) {

    public MapDataset {
        features = List.copyOf(features);
        if (features.isEmpty()) {
            throw new IllegalArgumentException(
                    "map dataset must contain at least one feature");
        }
        if (southWest.latitude() > northEast.latitude()
                || southWest.longitude() > northEast.longitude()) {
            throw new IllegalArgumentException("invalid map bounds");
        }
    }

    public static MapDataset from(List<MapFeature> features) {
        List<MapFeature> copy = List.copyOf(features);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "map dataset must contain at least one feature");
        }
        double south = Double.POSITIVE_INFINITY;
        double west = Double.POSITIVE_INFINITY;
        double north = Double.NEGATIVE_INFINITY;
        double east = Double.NEGATIVE_INFINITY;
        for (MapFeature feature : copy) {
            for (GeoPosition point : feature.points()) {
                south = Math.min(south, point.latitude());
                north = Math.max(north, point.latitude());
                west = Math.min(west, point.longitude());
                east = Math.max(east, point.longitude());
            }
        }
        return new MapDataset(copy, new GeoPosition(south, west),
                new GeoPosition(north, east));
    }
}
