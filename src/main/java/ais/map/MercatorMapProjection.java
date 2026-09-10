package ais.map;

import ais.domain.GeoPosition;

import java.util.Objects;

public final class MercatorMapProjection implements MapProjection {

    private static final double WGS84_FLATTENING =
            1.0 / 298.257223563;
    private static final double ECCENTRICITY = Math.sqrt(
            WGS84_FLATTENING * (2.0 - WGS84_FLATTENING));

    @Override
    public ProjectedMapPoint project(GeoPosition position) {
        Objects.requireNonNull(position, "position");
        double radians = Math.toRadians(position.latitude());
        double eccentricitySinLatitude =
                ECCENTRICITY * Math.sin(radians);
        double spherical = Math.log(
                Math.tan(Math.PI / 4.0 + radians / 2.0));
        double correction = ECCENTRICITY / 2.0 * Math.log(
                (1.0 + eccentricitySinLatitude)
                        / (1.0 - eccentricitySinLatitude));
        return new ProjectedMapPoint(
                position.longitude() * 60.0,
                Math.toDegrees(spherical - correction) * 60.0);
    }

    @Override
    public GeoPosition unproject(ProjectedMapPoint point) {
        Objects.requireNonNull(point, "point");
        double meridionalRadians = Math.toRadians(point.y() / 60.0);
        double latitude = 2.0 * Math.atan(Math.exp(meridionalRadians))
                - Math.PI / 2.0;
        for (int iteration = 0; iteration < 8; iteration++) {
            double eccentricitySin = ECCENTRICITY * Math.sin(latitude);
            double adjusted = Math.exp(meridionalRadians)
                    * Math.pow((1.0 + eccentricitySin)
                    / (1.0 - eccentricitySin), ECCENTRICITY / 2.0);
            latitude = 2.0 * Math.atan(adjusted) - Math.PI / 2.0;
        }
        return new GeoPosition(
                Math.toDegrees(latitude), point.x() / 60.0);
    }
}
