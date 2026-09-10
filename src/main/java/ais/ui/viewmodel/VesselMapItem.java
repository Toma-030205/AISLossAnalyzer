package ais.ui.viewmodel;

import ais.domain.FreshnessState;
import ais.domain.GeoPosition;
import ais.domain.TrailPoint;
import ais.domain.VesselClass;

import java.util.List;

public record VesselMapItem(
        int mmsi,
        VesselClass vesselClass,
        GeoPosition position,
        Double directionDegrees,
        FreshnessState freshness,
        List<TrailPoint> trail,
        boolean selected) {

    public VesselMapItem {
        trail = List.copyOf(trail);
    }
}
