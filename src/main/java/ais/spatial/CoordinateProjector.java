package ais.spatial;

import ais.domain.GeoPosition;

public interface CoordinateProjector {

    ProjectedPoint project(GeoPosition position);

    GeoPosition unproject(ProjectedPoint point);
}
