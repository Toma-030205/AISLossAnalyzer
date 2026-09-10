package ais.ui.viewmodel;

import ais.aggregate.MetricEvaluation;
import ais.spatial.GridCellId;

import java.util.Objects;

public record GridMapItem(
        GridCellId cell,
        MetricEvaluation evaluation,
        Double displayedRatePercent,
        boolean selected) {

    public GridMapItem {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(evaluation, "evaluation");
    }
}
