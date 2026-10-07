package ais.storage;

import ais.domain.AnalysisProfileId;
import ais.domain.ReceiverProfileId;

import java.time.LocalDate;
import java.util.Objects;

public record DailyDataQualityQuery(
        LocalDate startDate,
        LocalDate endDate,
        ReceiverProfileId receiverProfileId,
        AnalysisProfileId analysisProfileId) {

    public DailyDataQualityQuery {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(receiverProfileId, "receiverProfileId");
        Objects.requireNonNull(analysisProfileId, "analysisProfileId");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "endDate must not precede startDate");
        }
    }
}
