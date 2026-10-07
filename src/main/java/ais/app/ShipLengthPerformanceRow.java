package ais.app;

import ais.aggregate.MetricEvaluation;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.util.Objects;

public record ShipLengthPerformanceRow(
        ShipLengthBand shipLengthBand,
        DistanceBand distanceBand,
        VesselClass vesselClass,
        MetricEvaluation evaluation,
        int observationDayCount) {

    public ShipLengthPerformanceRow {
        Objects.requireNonNull(shipLengthBand, "shipLengthBand");
        Objects.requireNonNull(distanceBand, "distanceBand");
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(evaluation, "evaluation");
        if (observationDayCount < 0) {
            throw new IllegalArgumentException(
                    "observationDayCount must not be negative");
        }
    }

    public Double selectedRatePercent(
            ais.ui.viewmodel.HeatmapMetric metric) {
        return metric == ais.ui.viewmodel.HeatmapMetric.ESTIMATED_LOSS
                ? evaluation.lossRatePercent()
                : evaluation.freshnessViolationRatePercent();
    }
}
