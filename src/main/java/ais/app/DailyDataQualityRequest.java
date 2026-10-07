package ais.app;

import ais.domain.AnalysisProfileId;
import ais.domain.ReceiverProfileId;

import java.time.LocalDate;
import java.util.Objects;

public record DailyDataQualityRequest(
        LocalDate startDate,
        LocalDate endDate,
        ReceiverProfileId receiverProfileId,
        AnalysisProfileId analysisProfileId) {

    public DailyDataQualityRequest {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(receiverProfileId, "receiverProfileId");
        Objects.requireNonNull(analysisProfileId, "analysisProfileId");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "終了日は開始日以降にしてください");
        }
    }
}
