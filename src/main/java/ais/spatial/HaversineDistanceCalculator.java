package ais.spatial;

import ais.domain.GeoPosition;

import java.util.Objects;

public final class HaversineDistanceCalculator
        implements DistanceCalculator {

    private static final double EARTH_RADIUS_KILOMETERS = 6_371.0;

    @Override
    public double distanceKilometers(
            GeoPosition first,
            GeoPosition second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");

        double latitude1 = Math.toRadians(first.latitude());
        double latitude2 = Math.toRadians(second.latitude());
        double latitudeDifference = latitude2 - latitude1;
        double longitudeDifference = Math.toRadians(
                second.longitude() - first.longitude());

        double sineLatitude = Math.sin(latitudeDifference / 2.0);
        double sineLongitude = Math.sin(longitudeDifference / 2.0);
        double a = sineLatitude * sineLatitude
                + Math.cos(latitude1)
                * Math.cos(latitude2)
                * sineLongitude
                * sineLongitude;
        double centralAngle = 2.0 * Math.atan2(
                Math.sqrt(a),
                Math.sqrt(Math.max(0.0, 1.0 - a)));
        return EARTH_RADIUS_KILOMETERS * centralAngle;
    }
}
