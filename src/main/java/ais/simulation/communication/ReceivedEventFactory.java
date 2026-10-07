package ais.simulation.communication;

import ais.domain.PositionReport;
import ais.simulation.traffic.IdealTransmission;

import java.util.Objects;

public final class ReceivedEventFactory {

    public PositionReport create(
            IdealTransmission transmission,
            long sequence) {
        Objects.requireNonNull(transmission, "transmission");
        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "sequence must not be negative");
        }
        return new PositionReport(
                transmission.plannedAt(),
                sequence,
                transmission.messageType(),
                transmission.mmsi(),
                transmission.position(),
                transmission.sogKnots(),
                transmission.cogDegrees(),
                transmission.trueHeadingDegrees(),
                transmission.navigationStatus(),
                transmission.classBReportingMode(),
                transmission.assignedMode());
    }
}
