package ais.spatial;

import ais.domain.GeoPosition;

public interface DistanceCalculator {

    double distanceKilometers(GeoPosition first, GeoPosition second);
}
