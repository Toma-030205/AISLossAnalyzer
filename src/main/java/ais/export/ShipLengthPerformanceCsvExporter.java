package ais.export;

import ais.app.ShipLengthPerformanceResult;
import ais.app.ShipLengthPerformanceRow;
import ais.domain.VesselClass;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class ShipLengthPerformanceCsvExporter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB,
            (byte) 0xBF};

    public void write(Path target, ShipLengthPerformanceResult result)
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
                writer.write("# 期間（日本時間）: "
                        + result.request().startDate() + "～"
                        + result.request().endDate()
                        + ExportMetadataFormatter.excludedDates(
                        result.request().excludedDates())
                        + " / 指標: " + result.request().metric()
                        + " / 受信局: " + result.receiverProfile().name()
                        + " / 解析条件: "
                        + result.analysisProfile().rulesVersion());
                writer.newLine();
                writer.write("# 船体長: 同一Class・MMSIについて各観測日終了までに"
                        + "取得済みの最新Type 5/24値。1～500mを有効とする");
                writer.newLine();
                writer.write("# 注意: 船体長はアンテナ高の代理変数であり、"
                        + "航路・船種・運航範囲などの交絡を含む。"
                        + "率は個々の率の平均ではなく分子・分母の合算値");
                writer.newLine();
                writer.write(String.format(Locale.ROOT,
                        "# 新形式解析run: %,d / 再解析必要run: %,d / "
                                + "Class別MMSI: %,d / 船体長既知: %,d (%.1f%%)",
                        result.readyAnalysisRunCount(),
                        result.reanalysisRequiredRunCount(),
                        result.totalDistinctVesselCount(),
                        result.knownLengthDistinctVesselCount(),
                        result.knownLengthCoveragePercent()));
                writer.newLine();
                writer.write("船体長区分,距離帯,Class,受信位置数,"
                        + "推定欠落数,期待位置数,推定欠落率_%,"
                        + "観測秒,鮮度違反秒,情報鮮度違反率_%,"
                        + "Class別MMSI数,観測日数,標本判定");
                writer.newLine();
                for (ShipLengthPerformanceRow row : result.rows()) {
                    var evaluation = row.evaluation();
                    var counts = evaluation.counts();
                    writer.write(String.join(",",
                            csv(row.shipLengthBand().toString()),
                            csv(row.distanceBand().label()),
                            csv(classLabel(row.vesselClass())),
                            Long.toString(counts.observedCount()),
                            Long.toString(counts.missingCount()),
                            Long.toString(counts.expectedCount()),
                            decimal(evaluation.lossRatePercent()),
                            decimal(counts.observedSeconds()),
                            decimal(counts.staleSeconds()),
                            decimal(evaluation
                                    .freshnessViolationRatePercent()),
                            Integer.toString(
                                    evaluation.distinctVesselCount()),
                            Integer.toString(row.observationDayCount()),
                            csv(evaluation.hasSufficientData()
                                    ? "十分" : "データ不足")));
                    writer.newLine();
                }
            }
        }
    }

    private static String decimal(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.3f", value);
    }

    private static String classLabel(VesselClass vesselClass) {
        return vesselClass == VesselClass.CLASS_A ? "Class A" : "Class B";
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
