package ais.app;

import ais.domain.AnalysisProfileId;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record ShipLengthAnalysisRequest(
        LocalDate startDate,
        LocalDate endDate,
        Set<VesselClass> vesselClasses,
        ReceiverProfileId receiverProfileId,
        AnalysisProfileId analysisProfileId,
        Set<LocalDate> excludedDates) {

    public ShipLengthAnalysisRequest(
            LocalDate startDate,
            LocalDate endDate,
            Set<VesselClass> vesselClasses,
            ReceiverProfileId receiverProfileId,
            AnalysisProfileId analysisProfileId) {
        this(startDate, endDate, vesselClasses, receiverProfileId,
                analysisProfileId, Set.of());
    }

    public ShipLengthAnalysisRequest {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        vesselClasses = Set.copyOf(vesselClasses);
        Objects.requireNonNull(receiverProfileId, "receiverProfileId");
        Objects.requireNonNull(analysisProfileId, "analysisProfileId");
        excludedDates = Set.copyOf(excludedDates);
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "終了日は開始日以降にしてください");
        }
        if (vesselClasses.isEmpty()
                || vesselClasses.contains(VesselClass.UNKNOWN)) {
            throw new IllegalArgumentException(
                    "Class AまたはClass Bを選択してください");
        }
        if (excludedDates.stream().anyMatch(date ->
                date.isBefore(startDate) || date.isAfter(endDate))) {
            throw new IllegalArgumentException(
                    "除外日は集計期間内で指定してください");
        }
    }
}
