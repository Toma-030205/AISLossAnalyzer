package ais.export;

import ais.app.AggregateResult;

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

    private static String base(String kind, AggregateResult result) {
        String metric = result.request().metric().name().toLowerCase();
        String classes = result.request().vesselClasses().size() == 2
                ? "all" : result.request().vesselClasses().iterator().next()
                .name().toLowerCase();
        return "ais_" + kind + "_" + result.request().startDate()
                + "_" + result.request().endDate() + "_"
                + classes + "_" + metric;
    }

    private static String axis(AggregateResult result) {
        return result.request().axis() == ais.app.AggregateAxis.DISTANCE_BAND
                ? "distance" : "hour";
    }
}
