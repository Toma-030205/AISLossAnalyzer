package ais.storage;

import ais.domain.VesselClass;

import java.util.Objects;

public record StoredShipLengthCell(
        int shipLengthBandOrder,
        VesselClass vesselClass,
        int dailyMaximumDistanceBandIndex,
        long vesselDayCount,
        int cellDistinctVesselCount,
        long bandVesselDayCount,
        int bandDistinctVesselCount) {

    public StoredShipLengthCell {
        Objects.requireNonNull(vesselClass, "vesselClass");
        if (shipLengthBandOrder < 0 || dailyMaximumDistanceBandIndex < 0
                || vesselDayCount <= 0 || cellDistinctVesselCount <= 0
                || bandVesselDayCount < vesselDayCount
                || bandDistinctVesselCount < cellDistinctVesselCount) {
            throw new IllegalArgumentException(
                    "invalid stored ship-length cell");
        }
    }
}
