package ais.simulation.traffic;

import ais.domain.ClassBReportingMode;
import ais.domain.GeoPosition;
import ais.domain.VesselClass;

import java.time.Instant;
import java.util.Objects;

public record IdealTransmission(
        IdealTransmissionId id,
        int messageType,
        GeoPosition position,
        Double sogKnots,
        Double cogDegrees,
        Double trueHeadingDegrees,
        Integer navigationStatus,
        ClassBReportingMode classBReportingMode,
        boolean assignedMode,
        boolean changingCourse,
        TransmissionOrigin origin) {

    public IdealTransmission {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(classBReportingMode, "classBReportingMode");
        Objects.requireNonNull(origin, "origin");
        boolean classAType = messageType >= 1 && messageType <= 3;
        boolean classBType = messageType == 18;
        if ((!classAType && !classBType)
                || (classAType && id.vesselClass() != VesselClass.CLASS_A)
                || (classBType && id.vesselClass() != VesselClass.CLASS_B)) {
            throw new IllegalArgumentException(
                    "message type and vessel class do not match");
        }
    }

    public Instant plannedAt() {
        return id.plannedAt();
    }

    public int mmsi() {
        return id.mmsi();
    }

    public VesselClass vesselClass() {
        return id.vesselClass();
    }
}
