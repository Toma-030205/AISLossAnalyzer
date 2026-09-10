package ais.export;

import ais.app.AggregateResult;

public final class ExportMetadataFormatter {

    public String summary(AggregateResult result) {
        return "期間（日本時間）: " + result.request().startDate() + "～"
                + result.request().endDate()
                + " / 指標: " + result.request().metric()
                + " / Class: " + classLabel(result)
                + " / 集計単位: " + dimensionLabel(result)
                + " / 表示: " + result.request().axis()
                + " / 受信局: " + result.receiverProfile().name()
                + " / 解析条件: "
                + result.analysisProfile().rulesVersion();
    }

    public String classLabel(AggregateResult result) {
        if (result.request().vesselClasses().size() == 2) {
            return "全船舶";
        }
        return result.request().vesselClasses().stream()
                .findFirst()
                .map(value -> value == ais.domain.VesselClass.CLASS_A
                        ? "Class A" : "Class B")
                .orElse("—");
    }

    private static String dimensionLabel(AggregateResult result) {
        return switch (result.request().dimension()) {
            case DAY -> "日別";
            case DAY_OF_WEEK -> "曜日別";
            case MONTH -> "月別";
            case YEAR -> "年別（1～12月）";
            case HOUR_OF_DAY -> "時間帯別";
        };
    }
}
