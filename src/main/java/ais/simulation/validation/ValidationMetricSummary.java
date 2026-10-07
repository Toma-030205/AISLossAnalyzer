package ais.simulation.validation;

public record ValidationMetricSummary(
        ValidationMetric metric,
        Double modelWeightedMaePoints,
        Double baselineWeightedMaePoints,
        Double improvementPercent,
        int comparableCellCount,
        int withinObservedVariationCount,
        String transitionTrend,
        String classDifferenceTrend) {

    public ValidationMetricSummary {
        if (metric == null || comparableCellCount < 0
                || withinObservedVariationCount < 0
                || withinObservedVariationCount > comparableCellCount) {
            throw new IllegalArgumentException("invalid validation summary");
        }
        transitionTrend = transitionTrend == null ? "判定不能"
                : transitionTrend;
        classDifferenceTrend = classDifferenceTrend == null ? "判定不能"
                : classDifferenceTrend;
    }
}
