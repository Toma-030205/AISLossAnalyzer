package ais.export;

import ais.simulation.validation.StatisticalSummary;
import ais.simulation.validation.ValidationCellResult;
import ais.simulation.validation.ValidationMetric;
import ais.simulation.validation.ValidationMetricComparison;
import ais.simulation.validation.ValidationResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ValidationCsvExporter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB,
            (byte) 0xBF};

    public void write(Path target, ValidationResult result)
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
                metadata(writer, result);
                writer.write("指標,距離帯index,距離帯,Class,実測値%,"
                        + "実測日別2.5%,実測日別97.5%,CM-E1平均%,"
                        + "CM-E1中央値%,CM-E1最小%,CM-E1最大%,"
                        + "CM-E1変動下限%,CM-E1変動上限%,"
                        + "距離なし基準平均%,実測との差pp,"
                        + "基準の実測との差pp,期待送信数,受信数,"
                        + "推定欠落数,観測秒,鮮度違反秒,MMSI数,"
                        + "観測日数,判定");
                writer.newLine();
                for (ValidationMetric metric : ValidationMetric.values()) {
                    for (ValidationCellResult cell : result.cells()) {
                        writeCell(writer, metric, cell);
                    }
                }
            }
        }
    }

    private static void metadata(
            BufferedWriter writer,
            ValidationResult result) throws IOException {
        var summaryLoss = result.summary(ValidationMetric.ESTIMATED_LOSS);
        var summaryFreshness = result.summary(
                ValidationMetric.FRESHNESS_VIOLATION);
        writer.write("# 実験ID: " + result.experimentId());
        writer.newLine();
        writer.write("# モデル: " + result.model().modelCode().label()
                + " / " + result.model().id()
                + " / revision=" + result.model().revision());
        writer.newLine();
        writer.write("# 学習期間: " + result.model().trainingStartDate()
                + "～" + result.model().trainingEndDate());
        writer.newLine();
        writer.write("# 検証期間: " + result.request().startDate()
                + "～" + result.request().endDate());
        writer.newLine();
        writer.write("# 除外日: " + result.request().excludedDates());
        writer.newLine();
        writer.write("# 反復回数: " + result.request().iterationCount()
                + " / seeds=" + result.seeds());
        writer.newLine();
        writer.write("# 入力fingerprint: " + result.inputFingerprint());
        writer.newLine();
        writer.write("# 実測根拠run: " + result.observedSourceRunIds());
        writer.newLine();
        writer.write("# 欠落率要約: CM-E1 MAE="
                + number(summaryLoss.modelWeightedMaePoints())
                + "pp / 基準MAE="
                + number(summaryLoss.baselineWeightedMaePoints())
                + "pp / 改善率="
                + number(summaryLoss.improvementPercent()) + "%");
        writer.newLine();
        writer.write("# 鮮度要約: CM-E1 MAE="
                + number(summaryFreshness.modelWeightedMaePoints())
                + "pp / 基準MAE="
                + number(summaryFreshness.baselineWeightedMaePoints())
                + "pp / 改善率="
                + number(summaryFreshness.improvementPercent()) + "%");
        writer.newLine();
        writer.write("# 警告: " + result.warnings());
        writer.newLine();
    }

    private static void writeCell(
            BufferedWriter writer,
            ValidationMetric metric,
            ValidationCellResult cell) throws IOException {
        ValidationMetricComparison value = cell.comparison(metric);
        StatisticalSummary observedRange = value.observedDailyRange();
        StatisticalSummary simulation = value.simulation();
        StatisticalSummary baseline = value.baseline();
        var counts = cell.observedCounts();
        writer.write(String.join(",",
                csv(metric.toString()),
                Integer.toString(cell.key().distanceBand().index()),
                csv(cell.key().distanceBand().label()),
                cell.key().vesselClass().name(),
                number(value.observedPercent()),
                number(observedRange == null ? null
                        : observedRange.lower95()),
                number(observedRange == null ? null
                        : observedRange.upper95()),
                number(simulation == null ? null : simulation.mean()),
                number(simulation == null ? null : simulation.median()),
                number(simulation == null ? null : simulation.minimum()),
                number(simulation == null ? null : simulation.maximum()),
                number(simulation == null ? null : simulation.lower95()),
                number(simulation == null ? null : simulation.upper95()),
                number(baseline == null ? null : baseline.mean()),
                number(value.simulationDifferencePoints()),
                number(value.baselineDifferencePoints()),
                Long.toString(counts.expectedCount()),
                Long.toString(counts.observedCount()),
                Long.toString(counts.missingCount()),
                Double.toString(counts.observedSeconds()),
                Double.toString(counts.staleSeconds()),
                Integer.toString(cell.distinctVesselCount()),
                Integer.toString(cell.observationDayCount()),
                csv(value.status().toString())));
        writer.newLine();
    }

    private static String number(Double value) {
        return value == null ? "" : String.format(java.util.Locale.ROOT,
                "%.6f", value);
    }

    private static String csv(String value) {
        return "\"" + (value == null ? ""
                : value.replace("\"", "\"\"")) + "\"";
    }
}
