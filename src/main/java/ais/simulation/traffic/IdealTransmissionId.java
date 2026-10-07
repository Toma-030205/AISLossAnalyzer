package ais.simulation.traffic;

import ais.domain.VesselClass;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record IdealTransmissionId(
        LocalDate inputDate,
        int mmsi,
        VesselClass vesselClass,
        Instant plannedAt,
        int ordinal) {

    public IdealTransmissionId {
        Objects.requireNonNull(inputDate, "inputDate");
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(plannedAt, "plannedAt");
        if (mmsi <= 0 || mmsi > 999_999_999) {
            throw new IllegalArgumentException("invalid MMSI: " + mmsi);
        }
        if (vesselClass == VesselClass.UNKNOWN) {
            throw new IllegalArgumentException(
                    "ideal transmissions require Class A or Class B");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException(
                    "ordinal must not be negative");
        }
    }
}
