package ais.main;

import ais.logic.VesselOrganizer;
import ais.model.AisMessage;
import ais.model.Vessel;
import ais.parser.FileLoader;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
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

    // =====================================
    // 結果保存用
    // =====================================

    List<VesselStatisticsResult> type1Results =
            new ArrayList<>();

    List<VesselStatisticsResult> type5Results =
            new ArrayList<>();

    VesselStatistics statistics =
            new VesselStatistics();

    // =====================================
    // Message Typeごと解析
    // =====================================

    for (int type : new int[]{1, 5}) {

        for (Vessel vessel : vesselMap.values()) {

            boolean hasType =
                    vessel.getMessages()
                            .stream()
                            .anyMatch(
                                    m -> m.messageType == type);

            if (!hasType) {
                continue;
            }

            VesselStatisticsResult result =
                    statistics.analyze(vessel);

            if (result.totalMessages < 100) {
                continue;
            }

            if (type == 1) {
                type1Results.add(result);
            }

            if (type == 5) {
                type5Results.add(result);
            }
        }
    }

    // =====================================
    // 統計表示
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
        }
    
    
    private static void printSummary(
        String title,
        List<VesselStatisticsResult> results) {

    System.out.println();

    System.out.println(
            "----- " + title + " -----");

    System.out.println();

    System.out.println(
            "解析対象船舶数: "
                    + results.size());

    // =========================
    // 平均欠落率
    // =========================

    double avgLoss =
            results.stream()
                    .mapToDouble(
                            r -> r.lossRate)
                    .average()
                    .orElse(0);

    // =========================
    // 最大欠落率
    // =========================

    double maxLoss =
            results.stream()
                    .mapToDouble(
                            r -> r.lossRate)
                    .max()
                    .orElse(0);

    // =========================
    // 平均距離
    // =========================

    double avgDistance =
            results.stream()
                    .mapToDouble(
                            r -> r.averageDistance)
                    .average()
                    .orElse(0);

    // =========================
    // 最大距離
    // =========================

    double maxDistance =
            results.stream()
                    .mapToDouble(
                            r -> r.maxDistance)
                    .max()
                    .orElse(0);

    // =========================
    // 中央値
    // =========================

    List<Double> sortedLossRates =
            results.stream()
                    .map(r -> r.lossRate)
                    .sorted()
                    .toList();

    double medianLoss = 0;

    if (!sortedLossRates.isEmpty()) {

        int middle =
                sortedLossRates.size() / 2;

        medianLoss =
                sortedLossRates.get(middle);
    }

    // =========================
    // 出力
    // =========================

    System.out.printf(
            "平均欠落率: %.2f%%\n",
            avgLoss);

    System.out.printf(
            "中央値: %.2f%%\n",
            medianLoss);

    System.out.printf(
            "最大欠落率: %.2f%%\n",
            maxLoss);

    System.out.println();

    System.out.printf(
            "平均距離: %.1fkm\n",
            avgDistance);

    System.out.printf(
            "最大距離: %.1fkm\n",
            maxDistance);
}

        private static void exportCsv(
        String fileName,
        List<VesselStatisticsResult> results) {

    try {

        PrintWriter writer =
                new PrintWriter(fileName);

        // ヘッダ
        writer.println(
                "MMSI,LOSS_RATE,AVG_DISTANCE");

        // データ
        for (VesselStatisticsResult r : results) {

            writer.println(
                    r.mmsi + ","
                    + r.lossRate + ","
                    + r.averageDistance
            );
        }

        writer.close();

        System.out.println(
                "CSV出力完了: " + fileName);

    } catch (FileNotFoundException e) {

        e.printStackTrace();
    }
}
}