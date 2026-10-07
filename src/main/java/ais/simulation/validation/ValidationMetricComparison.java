package ais.simulation.validation;

public record ValidationMetricComparison(
        Double observedPercent,
        StatisticalSummary observedDailyRange,
        StatisticalSummary simulation,
        StatisticalSummary baseline,
        Double simulationDifferencePoints,
        Double baselineDifferencePoints,
        ValidationCellStatus status) {

    public ValidationMetricComparison {
        if (status == null) {
            throw new NullPointerException("status");
        }
    }
}
