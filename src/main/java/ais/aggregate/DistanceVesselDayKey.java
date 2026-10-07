package ais.aggregate;

import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Identifies one vessel's daily contribution to a distance-band metric.
 *
 * <p>This is retained separately from the five-minute aggregate so that a
 * vessel attribute, such as ship length, can be joined later without losing
 * the observed/missing and fresh/stale numerators and denominators.</p>
 */
public record DistanceVesselDayKey(
        LocalDate observedDate,
        DistanceBand distanceBand,
        VesselClass vesselClass,
        int mmsi) {

    public DistanceVesselDayKey {
        Objects.requireNonNull(observedDate, "observedDate");
        Objects.requireNonNull(distanceBand, "distanceBand");
        Objects.requireNonNull(vesselClass, "vesselClass");
        if (vesselClass == VesselClass.UNKNOWN) {
            throw new IllegalArgumentException(
                    "distance metrics require Class A or Class B");
        }
        if (mmsi <= 0) {
            throw new IllegalArgumentException("mmsi must be positive");
        }
    }
}
