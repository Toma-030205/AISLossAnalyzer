package ais.export;

import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.calibration.CommunicationParameter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class CommunicationModelCsvExporter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB,
            (byte) 0xBF};

    public void write(Path target, CommunicationModelDraft draft)
            throws IOException {
        Objects.requireNonNull(draft, "draft");
        write(target, List.of(
                        "# 状態: 保存前の試算",
                        "# モデル: " + draft.request().modelCode().label(),
                        "# 学習期間: " + draft.request().startDate()
                                + "～" + draft.request().endDate(),
                        "# 除外日: " + draft.request().excludedDates(),
                        "# 受信局ID: "
                                + draft.request().receiverProfileId().value(),
                        "# 解析条件ID: "
                                + draft.request().analysisProfileId().value(),
                        "# formula: " + draft.formulaVersion(),
                        "# bootstrap: "
                                + draft.request().bootstrapIterations()
                                + " / seed="
                                + draft.request().bootstrapSeed(),
                        "# 根拠run数: " + draft.sourceRunIds().size(),
                        "# 未解析日: " + draft.missingDates(),
                        "# 警告: " + draft.warnings()),
                draft.parameters());
    }

    public void write(Path target, CommunicationModelSnapshot snapshot)
            throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        var definition = snapshot.definition();
        write(target, List.of(
                        "# 状態: SQLite保存済み",
                        "# モデルID: " + definition.id(),
                        "# モデル: " + definition.modelCode().label()
                                + " / revision=" + definition.revision(),
                        "# 名称: " + definition.name(),
                        "# 学習期間: " + definition.trainingStartDate()
                                + "～" + definition.trainingEndDate(),
                        "# 除外日: " + snapshot.excludedDates(),
                        "# 受信局ID: "
                                + definition.receiverProfileId().value(),
                        "# 解析条件ID: "
                                + definition.analysisProfileId().value(),
                        "# formula: " + definition.formulaVersion(),
                        "# bootstrap: "
                                + definition.bootstrapIterations()
                                + " / seed=" + definition.bootstrapSeed(),
                        "# 作成日時UTC: " + definition.createdAt(),
                        "# 根拠run数: " + snapshot.sourceRunIds().size(),
                        "# メモ: " + (definition.notes() == null
                                ? "" : definition.notes())),
                snapshot.parameters());
    }

    private static void write(
            Path target,
            List<String> metadata,
            List<CommunicationParameter> parameters) throws IOException {
        Objects.requireNonNull(target, "target");
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        try (var output = Files.newOutputStream(absolute)) {
            output.write(UTF8_BOM);
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                for (String line : metadata) {
                    writer.write(line);
                    writer.newLine();
                }
                writer.write("距離帯index,距離帯,Class,受信数,推定欠落数,"
                        + "期待送信数,MMSI数,観測日数,生欠落率,生受信率,"
                        + "Jeffreys補正受信確率,CI下限,CI上限,適用状態,"
                        + "適用受信確率,近距離側補間元,遠距離側補間元");
                writer.newLine();
                for (CommunicationParameter parameter : parameters) {
                    var interval = parameter.confidenceInterval();
                    writer.write(String.join(",",
                            Integer.toString(
                                    parameter.distanceBand().index()),
                            csv(parameter.distanceBand().label()),
                            parameter.vesselClass().name(),
                            Long.toString(parameter.observedCount()),
                            Long.toString(parameter.missingCount()),
                            Long.toString(parameter.expectedCount()),
                            Integer.toString(
                                    parameter.distinctVesselCount()),
                            Integer.toString(parameter.observedDayCount()),
                            number(parameter.rawLossRate()),
                            number(parameter.rawReceptionRate()),
                            number(parameter.jeffreysReceptionProbability()),
                            number(interval == null
                                    ? null : interval.lower()),
                            number(interval == null
                                    ? null : interval.upper()),
                            parameter.applicability().name(),
                            number(parameter.appliedReceptionProbability()),
                            integer(parameter.lowerSourceBandIndex()),
                            integer(parameter.upperSourceBandIndex())));
                    writer.newLine();
                }
            }
        }
    }

    private static String number(Double value) {
        return value == null ? "" : Double.toString(value);
    }

    private static String integer(Integer value) {
        return value == null ? "" : Integer.toString(value);
    }

    private static String csv(String value) {
        String escaped = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
