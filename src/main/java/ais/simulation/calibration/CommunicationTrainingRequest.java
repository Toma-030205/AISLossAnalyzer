package ais.simulation.calibration;

import ais.domain.AnalysisProfileId;
import ais.domain.ReceiverProfileId;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record CommunicationTrainingRequest(
        CommunicationModelCode modelCode,
        LocalDate startDate,
        LocalDate endDate,
        Set<LocalDate> excludedDates,
        ReceiverProfileId receiverProfileId,
        AnalysisProfileId analysisProfileId,
        int bootstrapIterations,
        long bootstrapSeed) {

    public CommunicationTrainingRequest {
        Objects.requireNonNull(modelCode, "modelCode");
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(excludedDates, "excludedDates");
        Objects.requireNonNull(receiverProfileId, "receiverProfileId");
        Objects.requireNonNull(analysisProfileId, "analysisProfileId");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "endDate must not be before startDate");
        }
        excludedDates = Set.copyOf(excludedDates);
        for (LocalDate excluded : excludedDates) {
            if (excluded.isBefore(startDate) || excluded.isAfter(endDate)) {
                throw new IllegalArgumentException(
                        "excluded date is outside the training period: "
                                + excluded);
            }
        }
        if (bootstrapIterations < 1) {
            throw new IllegalArgumentException(
                    "bootstrapIterations must be at least one");
        }
    }
}
