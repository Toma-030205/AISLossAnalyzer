package ais.app;

import ais.aggregate.MetricEvaluation;
import ais.domain.VesselClass;

import java.util.Objects;

public record AggregateRow(
        String seriesLabel,
        String categoryLabel,
        VesselClass vesselClass,
        MetricEvaluation evaluation,
        int observationDayCount,
        int categoryOrder,
        Integer hourOfDay) {

    public AggregateRow(
            String seriesLabel,
            String categoryLabel,
            VesselClass vesselClass,
            MetricEvaluation evaluation,
            int observationDayCount,
            int categoryOrder) {
        this(seriesLabel, categoryLabel, vesselClass, evaluation,
                observationDayCount, categoryOrder, null);
    }

    public AggregateRow {
        if (seriesLabel == null || seriesLabel.isBlank()
                || categoryLabel == null || categoryLabel.isBlank()) {
            throw new IllegalArgumentException(
                    "aggregate labels must not be blank");
        }
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(evaluation, "evaluation");
        if (observationDayCount < 0 || categoryOrder < 0) {
            throw new IllegalArgumentException(
                    "aggregate ordering values must not be negative");
        }
        if (hourOfDay != null && (hourOfDay < 0 || hourOfDay > 23)) {
            throw new IllegalArgumentException(
                    "hourOfDay must be between 0 and 23");
        }
    }

    public Double displayedRate(AggregateRequest request) {
        return request.metric() == ais.ui.viewmodel.HeatmapMetric.ESTIMATED_LOSS
                ? evaluation.lossRatePercent()
                : evaluation.freshnessViolationRatePercent();
    }
}
