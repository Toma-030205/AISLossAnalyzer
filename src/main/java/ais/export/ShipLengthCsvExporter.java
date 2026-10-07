package ais.export;

import ais.app.ShipLengthAnalysisResult;
import ais.app.ShipLengthAnalysisRow;
import ais.app.ShipLengthDistanceCell;
import ais.domain.VesselClass;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class ShipLengthCsvExporter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB,
            (byte) 0xBF};

    public void write(Path target, ShipLengthAnalysisResult result)
            throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(result, "result");
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        try (var output = Files.newOutputStream(absolute)) {
            output.write(UTF8_BOM);
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                writeMetadata(writer, result);
                writeSummary(writer, result);
                writer.newLine();
                writeDistribution(writer, result);
            }
        }
    }

    private static void writeMetadata(
            BufferedWriter writer,
            ShipLengthAnalysisResult result) throws IOException {
        writer.write("# 期間（日本時間）: "
                + result.request().startDate() + "～"
                + result.request().endDate()
                + ExportMetadataFormatter.excludedDates(
                result.request().excludedDates())
                + " / 受信局: " + result.receiverProfile().name()
                + " / 解析条件: "
                + result.analysisProfile().rulesVersion());
        writer.newLine();
        writer.write("# 定義: 各Class・MMSIについて、1日ごとに"
                + "0～" + result.analysisProfile()
                .maximumDistanceKilometers()
                + "km内の解析航跡が到達した最遠5km帯を1標本とする");
        writer.newLine();
        writer.write("# 船体長: Type 5/24から得た同一Class・MMSIについて、"
                + "各観測日の終了時点までに取得済みの最新非null値。"
                + "1～500mを有効、その他を不明・範囲外とする");
        writer.newLine();
        writer.write("# 注意: 欠落率・情報鮮度違反率ではない。"
                + "船体長はアンテナ高の代理変数であり、"
                + "航路・船種・運航範囲の影響を含む。"
                + "70km外だけに存在した船舶日は距離表に含まれない");
        writer.newLine();
        writer.write(String.format(Locale.ROOT,
                "# 使用解析run: %,d / Class別MMSI標本: %,d / "
                        + "船体長既知: %,d (%.1f%%) / "
                        + "船舶日: %,d / 船体長既知船舶日: %,d (%.1f%%)",
                result.analysisRunCount(),
                result.totalDistinctVesselCount(),
                result.knownLengthDistinctVesselCount(),
                result.knownLengthCoveragePercent(),
                result.totalVesselDayCount(),
                result.knownLengthVesselDayCount(),
                result.knownLengthVesselDayCoveragePercent()));
        writer.newLine();
    }

    private static void writeSummary(
            BufferedWriter writer,
            ShipLengthAnalysisResult result) throws IOException {
        writer.write("船体長区分,Class,Class別MMSI数,船舶日数,"
                + "日別最遠距離帯下限の平均_km,日別最遠距離帯の中央値,"
                + "30km以上到達_船舶日,30km以上到達率_%,"
                + "50km以上到達_船舶日,50km以上到達率_%,標本判定");
        writer.newLine();
        for (ShipLengthAnalysisRow row : result.rows()) {
            writer.write(String.join(",",
                    csv(row.shipLengthBand().toString()),
                    csv(classLabel(row.vesselClass())),
                    Integer.toString(row.distinctVesselCount()),
                    Long.toString(row.vesselDayCount()),
                    decimal(row.averageDailyMaximumLowerKilometers()),
                    csv(row.medianDailyMaximumDistanceBand()),
                    Long.toString(row.atLeastThirtyKilometerVesselDays()),
                    decimal(row.atLeastThirtyKilometerRatePercent()),
                    Long.toString(row.atLeastFiftyKilometerVesselDays()),
                    decimal(row.atLeastFiftyKilometerRatePercent()),
                    csv(row.sufficientData() ? "十分" : "データ不足")));
            writer.newLine();
        }
    }

    private static void writeDistribution(
            BufferedWriter writer,
            ShipLengthAnalysisResult result) throws IOException {
        writer.write("船体長区分,Class,日別最遠距離帯,"
                + "船舶日数,当該セルClass別MMSI数,構成比_%,標本判定");
        writer.newLine();
        for (ShipLengthDistanceCell cell : result.cells()) {
            writer.write(String.join(",",
                    csv(cell.shipLengthBand().toString()),
                    csv(classLabel(cell.vesselClass())),
                    csv(cell.dailyMaximumDistanceBand().label()),
                    Long.toString(cell.vesselDayCount()),
                    Integer.toString(cell.distinctVesselCount()),
                    decimal(cell.sharePercent()),
                    csv(cell.sufficientData() ? "十分" : "データ不足")));
            writer.newLine();
        }
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String classLabel(VesselClass vesselClass) {
        return vesselClass == VesselClass.CLASS_A ? "Class A" : "Class B";
    }

    private static String csv(String value) {
        String escaped = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
