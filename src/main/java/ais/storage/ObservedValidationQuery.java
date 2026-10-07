package ais.storage;

import ais.domain.AnalysisProfileId;
import ais.domain.ReceiverProfileId;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record ObservedValidationQuery(
        LocalDate startDate,
        LocalDate endDate,
        ReceiverProfileId receiverProfileId,
        AnalysisProfileId analysisProfileId,
        Set<LocalDate> excludedDates) {

    public ObservedValidationQuery {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(receiverProfileId, "receiverProfileId");
        Objects.requireNonNull(analysisProfileId, "analysisProfileId");
        excludedDates = Set.copyOf(excludedDates);
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("invalid validation period");
        }
    }
}
