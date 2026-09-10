package ais.spatial;

import ais.domain.GeoPosition;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import java.util.Objects;

public final class Utm53NProjector implements CoordinateProjector {

    public static final String TARGET_EPSG = "EPSG:32653";

    private final CoordinateTransform forward;
    private final CoordinateTransform inverse;

    public Utm53NProjector() {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem geographic =
                crsFactory.createFromName("EPSG:4326");
        CoordinateReferenceSystem projected =
                crsFactory.createFromName(TARGET_EPSG);
        CoordinateTransformFactory transformFactory =
                new CoordinateTransformFactory();
        forward = transformFactory.createTransform(geographic, projected);
        inverse = transformFactory.createTransform(projected, geographic);
    }

    @Override
    public ProjectedPoint project(GeoPosition position) {
        Objects.requireNonNull(position, "position");
        ProjCoordinate result = forward.transform(
                new ProjCoordinate(
                        position.longitude(),
                        position.latitude()),
                new ProjCoordinate());
        return new ProjectedPoint(result.x, result.y);
    }

    @Override
    public GeoPosition unproject(ProjectedPoint point) {
        Objects.requireNonNull(point, "point");
        ProjCoordinate result = inverse.transform(
                new ProjCoordinate(point.easting(), point.northing()),
                new ProjCoordinate());
        return new GeoPosition(result.y, result.x);
    }
}
