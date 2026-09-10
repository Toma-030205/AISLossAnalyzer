package ais.export;

import ais.aggregate.InsufficientDataReason;
import ais.aggregate.MetricCounts;
import ais.app.AggregateResult;
import ais.app.AggregateRow;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Collectors;

public final class CsvExporter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB,
            (byte) 0xBF};

    public void write(Path target, AggregateResult result) throws IOException {
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
                writer.write("# " + new ExportMetadataFormatter()
                        .summary(result));
                writer.newLine();
                writer.write("# 解析実行数: " + result.analysisRunCount());
                writer.newLine();
                writer.write("系列,区分,Class,observed,missing,expected,"
                        + "loss_rate_percent,observed_seconds,stale_seconds,"
                        + "freshness_violation_rate_percent,異なる船舶数,"
                        + "観測日数,データ判定");
                writer.newLine();
                for (AggregateRow row : result.rows()) {
                    MetricCounts counts = row.evaluation().counts();
                    writer.write(String.join(",",
                            csv(row.seriesLabel()),
                            csv(row.categoryLabel()),
                            csv(classLabel(row)),
                            Long.toString(counts.observedCount()),
                            Long.toString(counts.missingCount()),
                            Long.toString(counts.expectedCount()),
                            number(row.evaluation().lossRatePercent()),
                            decimal(counts.observedSeconds()),
                            decimal(counts.staleSeconds()),
                            number(row.evaluation()
                                    .freshnessViolationRatePercent()),
                            Integer.toString(row.evaluation()
                                    .distinctVesselCount()),
                            Integer.toString(row.observationDayCount()),
                            csv(sufficiency(row))));
                    writer.newLine();
                }
            }
        }
    }

    private static String sufficiency(AggregateRow row) {
        if (row.evaluation().hasSufficientData()) {
            return "十分";
        }
        return row.evaluation().insufficientReasons().stream()
                .map(InsufficientDataReason::name)
                .sorted()
                .collect(Collectors.joining("+"));
    }

    private static String classLabel(AggregateRow row) {
        return row.vesselClass() == ais.domain.VesselClass.CLASS_A
                ? "Class A" : "Class B";
    }

    private static String number(Double value) {
        return value == null ? "" : String.format(java.util.Locale.ROOT,
                "%.6f", value);
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String csv(String value) {
        String escaped = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
