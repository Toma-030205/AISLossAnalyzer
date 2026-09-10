package ais.map;

import ais.domain.GeoPosition;

public interface MapProjection {

    ProjectedMapPoint project(GeoPosition position);

    GeoPosition unproject(ProjectedMapPoint point);
}
