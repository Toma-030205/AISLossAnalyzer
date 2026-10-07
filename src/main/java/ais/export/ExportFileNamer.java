package ais.export;

import ais.app.AggregateResult;
import ais.app.DailyDataQualityResult;
import ais.app.ShipLengthAnalysisResult;
import ais.app.ShipLengthPerformanceResult;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.validation.ValidationMetric;
import ais.simulation.validation.ValidationResult;

public final class ExportFileNamer {

    public String csv(AggregateResult result) {
        return base(axis(result), result) + ".csv";
    }

    public String chartPng(AggregateResult result) {
        return base(axis(result), result) + ".png";
    }

    public String mapPng(AggregateResult result) {
        return base("map", result) + ".png";
    }

    public String dataQualityCsv(DailyDataQualityResult result) {
        return "ais_data_quality_" + result.request().startDate()
                + "_" + result.request().endDate() + ".csv";
    }

    public String shipLengthCsv(ShipLengthAnalysisResult result) {
        return shipLengthBase(result) + ".csv";
    }

    public String shipLengthChartPng(ShipLengthAnalysisResult result) {
        return shipLengthBase(result) + ".png";
    }

    public String shipLengthPerformanceCsv(
            ShipLengthPerformanceResult result) {
        return shipLengthPerformanceBase(result) + ".csv";
    }

    public String shipLengthPerformanceChartPng(
            ShipLengthPerformanceResult result) {
        return shipLengthPerformanceBase(result) + ".png";
    }

    public String communicationModelCsv(CommunicationModelDraft draft) {
        return "ais_communication_model_"
                + draft.request().modelCode().name().toLowerCase()
                + "_" + draft.request().startDate()
                + "_" + draft.request().endDate() + "_preview.csv";
    }

    public String communicationModelCsv(
            CommunicationModelSnapshot snapshot) {
        return "ais_communication_model_"
                + snapshot.definition().modelCode().name().toLowerCase()
                + "_" + snapshot.definition().trainingStartDate()
                + "_" + snapshot.definition().trainingEndDate()
                + "_rev" + snapshot.definition().revision() + ".csv";
    }

    public String validationCsv(ValidationResult result) {
        return validationBase(result) + ".csv";
    }

    public String validationChartPng(
            ValidationResult result,
            ValidationMetric metric) {
        return validationBase(result) + "_"
                + metric.name().toLowerCase() + ".png";
    }

    private static String validationBase(ValidationResult result) {
        return "ais_model_validation_"
                + result.request().startDate() + "_"
                + result.request().endDate() + "_rev"
                + result.model().revision()
                + exclusionSuffix(result.request().excludedDates());
    }

    private static String shipLengthPerformanceBase(
            ShipLengthPerformanceResult result) {
        String classes = result.request().vesselClasses().size() == 2
                ? "all" : result.request().vesselClasses().iterator().next()
                .name().toLowerCase();
        String metric = result.request().metric().name().toLowerCase();
        return "ais_ship_length_performance_"
                + result.request().startDate() + "_"
                + result.request().endDate() + "_"
                + classes + "_" + metric
                + exclusionSuffix(result.request().excludedDates());
    }

    private static String shipLengthBase(ShipLengthAnalysisResult result) {
        String classes = result.request().vesselClasses().size() == 2
                ? "all" : result.request().vesselClasses().iterator().next()
                .name().toLowerCase();
        return "ais_ship_length_reach_" + result.request().startDate()
                + "_" + result.request().endDate() + "_" + classes
                + exclusionSuffix(result.request().excludedDates());
    }

    private static String base(String kind, AggregateResult result) {
        String metric = result.request().metric().name().toLowerCase();
        String classes = result.request().vesselClasses().size() == 2
                ? "all" : result.request().vesselClasses().iterator().next()
                .name().toLowerCase();
        return "ais_" + kind + "_" + result.request().startDate()
                + "_" + result.request().endDate() + "_"
                + classes + "_" + metric
                + exclusionSuffix(result.request().excludedDates());
    }

    private static String exclusionSuffix(
            java.util.Set<java.time.LocalDate> dates) {
        if (dates.isEmpty()) {
            return "";
        }
        return "_exclude_" + dates.stream()
                .sorted()
                .map(date -> date.toString().replace("-", ""))
                .collect(java.util.stream.Collectors.joining("-"));
    }

    private static String axis(AggregateResult result) {
        return switch (result.request().axis()) {
            case DISTANCE_BAND -> "distance";
            case HOUR_OF_DAY -> "hour";
            case DISTANCE_BY_HOUR -> "distance_hour";
        };
    }
}
