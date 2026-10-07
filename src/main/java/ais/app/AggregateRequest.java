package ais.app;

import ais.aggregate.RollupDimension;
import ais.domain.AnalysisProfileId;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.ui.viewmodel.HeatmapMetric;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record AggregateRequest(
        LocalDate startDate,
        LocalDate endDate,
        RollupDimension dimension,
        Set<VesselClass> vesselClasses,
        HeatmapMetric metric,
        ReceiverProfileId receiverProfileId,
        AnalysisProfileId analysisProfileId,
        AggregateAxis axis,
        Set<LocalDate> excludedDates) {

    public AggregateRequest(
            LocalDate startDate,
            LocalDate endDate,
            RollupDimension dimension,
            Set<VesselClass> vesselClasses,
            HeatmapMetric metric,
            ReceiverProfileId receiverProfileId,
            AnalysisProfileId analysisProfileId,
            AggregateAxis axis) {
        this(startDate, endDate, dimension, vesselClasses, metric,
                receiverProfileId, analysisProfileId, axis, Set.of());
    }

    public AggregateRequest {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(dimension, "dimension");
        vesselClasses = Set.copyOf(vesselClasses);
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(receiverProfileId, "receiverProfileId");
        Objects.requireNonNull(analysisProfileId, "analysisProfileId");
        Objects.requireNonNull(axis, "axis");
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
