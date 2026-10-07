package ais.export;

import ais.app.DailyDataQualityResult;
import ais.app.DailyDataQualityRow;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class DailyDataQualityCsvExporter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB,
            (byte) 0xBF};
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    public void write(Path target, DailyDataQualityResult result)
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
                        + " / 受信局: " + result.receiverProfile().name()
                        + " / 解析条件: "
                        + result.analysisProfile().rulesVersion());
                writer.newLine();
                writer.write("# 解析5分枠は0～70 kmの保存済み距離集計を基準"
                        + "（30分以上間隔は船舶ごとの間隔であり、"
                        + "受信局停止を意味しない）");
                writer.newLine();
                writer.write("日付,5分枠判定,入力ファイル,入力ファイル数,"
                        + "展開後バイト,解析枠開始_JST,解析枠終了_JST,"
                        + "解析5分枠数,期待5分枠数,0-70km船舶数,"
                        + "正常デコードAIS件数,採用区間,推定欠落,重複,"
                        + "入力形式復号診断,位置利用不可,"
                        + "船舶別30分以上間隔,距離飛び,70km外,注意");
                writer.newLine();
                for (DailyDataQualityRow row : result.rows()) {
                    writer.write(String.join(",",
                            row.date().toString(),
                            csv(row.state().toString()),
                            csv(row.inputName()),
                            Integer.toString(row.inputFileCount()),
                            Long.toString(row.inputUncompressedBytes()),
                            csv(localDateTime(row.firstAggregateBucket())),
                            csv(localDateTime(row.lastAggregateBucket())),
                            Integer.toString(row.aggregateBucketCount()),
                            Integer.toString(
                                    DailyDataQualityRow
                                            .EXPECTED_BUCKETS_PER_DAY),
                            Integer.toString(row.distinctVesselCount()),
                            Long.toString(row.decodedAisEventCount()),
                            Long.toString(row.acceptedIntervalCount()),
                            Long.toString(row.estimatedMissingCount()),
                            Long.toString(row.duplicateCount()),
                            Long.toString(row.inputAnomalyCount()),
                            Long.toString(row.invalidPositionCount()),
                            Long.toString(row.thirtyMinuteGapCount()),
                            Long.toString(row.distanceJumpCount()),
                            Long.toString(row.outsideDistanceRangeCount()),
                            csv(row.note())));
                    writer.newLine();
                }
            }
        }
    }

    private static String localDateTime(Instant value) {
        return value == null ? ""
                : DATE_TIME.format(value.atZone(JAPAN));
    }

    private static String csv(String value) {
        String escaped = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
