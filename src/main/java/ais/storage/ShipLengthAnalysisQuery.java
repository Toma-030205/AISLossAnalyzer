package ais.storage;

import ais.domain.AnalysisProfileId;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record ShipLengthAnalysisQuery(
        LocalDate startDate,
        LocalDate endDate,
        Set<VesselClass> vesselClasses,
        ReceiverProfileId receiverProfileId,
        AnalysisProfileId analysisProfileId,
        Set<LocalDate> excludedDates) {

    public ShipLengthAnalysisQuery(
            LocalDate startDate,
            LocalDate endDate,
            Set<VesselClass> vesselClasses,
            ReceiverProfileId receiverProfileId,
            AnalysisProfileId analysisProfileId) {
        this(startDate, endDate, vesselClasses, receiverProfileId,
                analysisProfileId, Set.of());
    }

    public ShipLengthAnalysisQuery {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        vesselClasses = Set.copyOf(vesselClasses);
        Objects.requireNonNull(receiverProfileId, "receiverProfileId");
        Objects.requireNonNull(analysisProfileId, "analysisProfileId");
        excludedDates = Set.copyOf(excludedDates);
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "endDate must not precede startDate");
        }
        if (vesselClasses.isEmpty()
                || vesselClasses.contains(VesselClass.UNKNOWN)) {
            throw new IllegalArgumentException(
                    "query requires Class A and/or Class B");
        }
    }
}
