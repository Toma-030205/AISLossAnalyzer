package ais.main;

import ais.parser.FileLoader;
import ais.stats.DailyStatisticsResult;
import ais.stats.DistanceBinStatistics;
import ais.stats.StreamingVesselStatistics;
import ais.stats.VesselStatisticsResult;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.io.File;
import java.io.PrintWriter;
import java.io.FileNotFoundException;

public class Main {

    public static void main(String[] args) {

        String dataDirPath =
                "C:/Users/Owner/AISData";

        FileLoader loader =
                new FileLoader();

        StreamingVesselStatistics statistics =
                new StreamingVesselStatistics();

        long totalMessages =
                0;

        File[] dataFiles =
                new File(dataDirPath)
                        .listFiles((dir, name) ->
                                name.toLowerCase()
                                        .endsWith(".ais"));

        if (dataFiles == null || dataFiles.length == 0) {

            System.out.println(
                    "AIS files not found: "
                            + dataDirPath);

            return;
        }

        Arrays.sort(
                dataFiles,
                Comparator.comparing(
                        File::getName));

        for (File dataFile : dataFiles) {

            System.out.println(
                    "Loading: "
                            + dataFile.getName());

            final long[] fileMessageCount =
                    {0};

            loader.loadFile(
                    dataFile.getAbsolutePath(),
                    msg -> {

                        statistics.accept(msg);
                        fileMessageCount[0]++;
                    });

            totalMessages +=
                    fileMessageCount[0];
        }

        System.out.println(
                "===== AIS LOSS SUMMARY =====");

        System.out.println();

        System.out.println(
                "総メッセージ数: "
                        + totalMessages);

        System.out.println(
                "総船舶数: "
                        + statistics.getVesselCount());

        List<VesselStatisticsResult> type1Results =
                statistics.getResults(
                        1,
                        100);

        List<VesselStatisticsResult> type5Results =
                statistics.getResults(
                        5,
                        10);

        List<VesselStatisticsResult> type18Results =
                statistics.getResults(
                        18,
                        100);

        // =====================================
        // Type1解析
        // =====================================

        // =====================================
        // Type5解析
        // =====================================

        // =====================================
        // Type18隗｣譫・
        // =====================================

        // =====================================
        // 表示
        // =====================================

        printSummary(
                "Message Type 1",
                type1Results);

        printSummary(
                "Message Type 5",
                type5Results);

        printSummary(
                "Message Type 18",
                type18Results);

        // =====================================
        // CSV出力
        // =====================================

        exportCsv(
                "type1_distance_loss.csv",
                type1Results);

        exportCsv(
                "type5_distance_loss.csv",
                type5Results);

        exportCsv(
                "type18_distance_loss.csv",
                type18Results);

        exportDailyCsv(
                "type1_daily_distance_loss.csv",
                statistics.getDailyResults(1));

        exportDailyCsv(
                "type5_daily_distance_loss.csv",
                statistics.getDailyResults(5));

        exportDailyCsv(
                "type18_daily_distance_loss.csv",
                statistics.getDailyResults(18));
    }

    private static void printSummary(
        String title,
        List<VesselStatisticsResult> results) {

    System.out.println();

    System.out.println(
            "----- " + title + " -----");

    System.out.println();

    // =====================================
    // 船舶数
    // =====================================

    System.out.println(
            "解析対象船舶数: "
                    + results.size());

    // =====================================
    // 空チェック
    // =====================================

    if (results.isEmpty()) {
        return;
    }

    // =====================================
    // 統計計算用
    // =====================================

    double lossRateSum = 0;

    double maxLossRate = 0;

    double distanceSum = 0;

    double maxDistance = 0;

    List<Double> lossRates =
            new ArrayList<>();

    // =====================================
    // 集計
    // =====================================

    for (VesselStatisticsResult r : results) {

        // 欠落率
        lossRateSum += r.lossRate;

        lossRates.add(r.lossRate);

        if (r.lossRate > maxLossRate) {
            maxLossRate = r.lossRate;
        }

        if (r.maxDistance > maxDistance) {
            maxDistance = r.maxDistance;
        }
    }

    // =====================================
    // 平均
    // =====================================

    double averageLossRate =
            lossRateSum / results.size();

    double averageDistance =
            distanceSum / results.size();

    // =====================================
    // 中央値
    // =====================================

    lossRates.sort(Double::compareTo);

    double medianLossRate;

    int n = lossRates.size();

    if (n % 2 == 0) {

        medianLossRate =
                (lossRates.get(n / 2 - 1)
                        + lossRates.get(n / 2))
                        / 2.0;

    } else {

        medianLossRate =
                lossRates.get(n / 2);
    }

    // =====================================
    // 表示
    // =====================================

    System.out.printf(
            "平均欠落率: %.2f%%\n",
            averageLossRate);

    System.out.printf(
            "中央値: %.2f%%\n",
            medianLossRate);

    System.out.printf(
            "最大欠落率: %.2f%%\n",
            maxLossRate);

    System.out.println();

    System.out.printf(
            "最大距離: %.1fkm\n",
            maxDistance);

    System.out.println();
}

    private static void exportCsv(
        String fileName,
        List<VesselStatisticsResult> results) {

    try {

        PrintWriter writer =
                new PrintWriter(
                        fileName);

        writer.println(
                "MMSI,DISTANCE_BIN,SHIP_LENGTH,OBSERVED,EXPECTED,LOSS,LOSS_RATE,AVG_DISTANCE");

        for (
                VesselStatisticsResult r
                : results
        ) {

            for (
                    DistanceBinStatistics bin
                    : r.getDistanceBins()
            ) {

                writer.println(
                        r.mmsi
                        + ","
                        + bin.getBinLabel()
                        + ","
                        + (
                        r.shipLength
                                == null
                                ? ""
                                : r.shipLength
                )
                        + ","
                        + bin.observed
                        + ","
                        + bin.getExpected()
                        + ","
                        + bin.loss
                        + ","
                        + bin.getLossRate()
                        + ","
                        + bin.getAverageDistance()
                );
            }
        }

        writer.close();

    } catch (
            FileNotFoundException e
    ) {

        e.printStackTrace();
    }
}

    private static void exportDailyCsv(
        String fileName,
        List<DailyStatisticsResult> results) {

    try {

        PrintWriter writer =
                new PrintWriter(
                        fileName);

        writer.println(
                "DATE,DAY_OF_WEEK,MESSAGE_TYPE,MMSI,DISTANCE_BIN,SHIP_LENGTH,OBSERVED,EXPECTED,LOSS,LOSS_RATE,AVG_DISTANCE");

        for (
                DailyStatisticsResult r
                : results
        ) {

            for (
                    DistanceBinStatistics bin
                    : r.getDistanceBins()
            ) {

                writer.println(
                        r.date
                        + ","
                        + r.dayOfWeek
                        + ","
                        + r.messageType
                        + ","
                        + r.mmsi
                        + ","
                        + bin.getBinLabel()
                        + ","
                        + (
                        r.shipLength
                                == null
                                ? ""
                                : r.shipLength
                )
                        + ","
                        + bin.observed
                        + ","
                        + bin.getExpected()
                        + ","
                        + bin.loss
                        + ","
                        + bin.getLossRate()
                        + ","
                        + bin.getAverageDistance()
                );
            }
        }

        writer.close();

    } catch (
            FileNotFoundException e
    ) {

        e.printStackTrace();
    }
}
}
