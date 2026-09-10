package ais.ui.viewmodel;

import ais.aggregate.MetricEvaluation;
import ais.spatial.GridCellId;

public record GridDetailViewModel(
        GridCellId cell,
        MetricEvaluation evaluation,
        Double displayedRatePercent,
        double receiverDistanceKilometers) {
}
