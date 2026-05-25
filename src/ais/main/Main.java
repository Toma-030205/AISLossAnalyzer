package ais.main;

import ais.logic.VesselOrganizer;
import ais.model.AisMessage;
import ais.model.Vessel;
import ais.parser.FileLoader;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import ais.stats.VesselStatistics;
import ais.stats.VesselStatisticsResult;
import java.io.PrintWriter;
import java.io.FileNotFoundException;

public class Main {

    public static void main(String[] args) {

        String filePath =
                "C:/Users/Owner/AISData/260401-0.ais";

        FileLoader loader =
                new FileLoader();

        List<AisMessage> messages =
                loader.loadFile(filePath);

        System.out.println(
                "===== AIS LOSS SUMMARY =====");

        System.out.println();

        System.out.println(
                "総メッセージ数: "
                        + messages.size());

        VesselOrganizer organizer =
                new VesselOrganizer();

        Map<Integer, Vessel> vesselMap =
                organizer.organizeByMmsi(messages);

        System.out.println(
                "総船舶数: "
                        + vesselMap.size());

        List<VesselStatisticsResult> type1Results =
                new ArrayList<>();

        List<VesselStatisticsResult> type5Results =
                new ArrayList<>();

        VesselStatistics statistics =
                new VesselStatistics();

        // =====================================
        // Type1解析
        // =====================================

        for (Vessel vessel : vesselMap.values()) {

            VesselStatisticsResult result =
                    statistics.analyze(vessel, 1);

            if (result.totalMessages < 100) {
                continue;
            }

            type1Results.add(result);
        }

        // =====================================
        // Type5解析
        // =====================================

        for (Vessel vessel : vesselMap.values()) {

            VesselStatisticsResult result =
                    statistics.analyze(vessel, 5);

            if (result.totalMessages < 10) {
                continue;
            }

            type5Results.add(result);
        }

        // =====================================
        // 表示
        // =====================================

        printSummary(
                "Message Type 1",
                type1Results);

        printSummary(
                "Message Type 5",
                type5Results);

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
                "type1_distance_loss_with_length.csv",
                type1Results);
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
                "MMSI,LOSS_COUNT,DISTANCE,SHIP_LENGTH");

        for (
                VesselStatisticsResult r
                : results
        ) {

            for (
                    int i = 0;
                    i < r.lossDistances.size();
                    i++
            ) {

                writer.println(
                        r.mmsi
                        + ","
                        + r.lossCounts.get(i)
                        + ","
                        + r.lossDistances.get(i)
                        + ","
                        + (
                        r.shipLength
                                == null
                                ? ""
                                : r.shipLength
                )
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