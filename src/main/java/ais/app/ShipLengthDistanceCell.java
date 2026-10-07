package ais.app;

import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.util.Objects;

public record ShipLengthDistanceCell(
        ShipLengthBand shipLengthBand,
        VesselClass vesselClass,
        DistanceBand dailyMaximumDistanceBand,
        long vesselDayCount,
        int distinctVesselCount,
        double sharePercent,
        boolean sufficientData) {

    public ShipLengthDistanceCell {
        Objects.requireNonNull(shipLengthBand, "shipLengthBand");
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(
                dailyMaximumDistanceBand, "dailyMaximumDistanceBand");
        if (vesselDayCount < 0 || distinctVesselCount < 0
                || !Double.isFinite(sharePercent)
                || sharePercent < 0.0 || sharePercent > 100.0 + 1.0e-9) {
            throw new IllegalArgumentException(
                    "invalid ship-length distance cell");
        }
    }
}
