package ais.main;

import ais.parser.FileLoader;
import ais.parser.FileLoadStatistics;
import ais.stats.DailyStatisticsResult;
import ais.stats.DistanceBinStatistics;
import ais.stats.StreamingVesselStatistics;
import ais.stats.VesselStatisticsResult;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.io.File;
import java.io.PrintWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) {

        Path dataDir =
                args.length >= 1
                        ? Path.of(args[0])
                        : Path.of(
                                "C:/Users/Owner/AISData");

        Path outputDir =
                args.length >= 2
                        ? Path.of(args[1])
                        : Path.of(".");

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Could not create output directory: "
                            + outputDir,
                    e);
        }

        FileLoader loader =
                new FileLoader();

        StreamingVesselStatistics statistics =
                new StreamingVesselStatistics();

        long totalMessages =
                0;

        File[] dataFiles =
                dataDir
                        .toFile()
                        .listFiles((dir, name) ->
                                isSupportedInputFile(name));

        if (dataFiles == null || dataFiles.length == 0) {

            System.out.println(
                    "AIS files not found: "
                            + dataDir);

            return;
        }

        Arrays.sort(
                dataFiles,
                Comparator.comparing(
                        File::getName));

        dataFiles = removeCompressedCounterpartDuplicates(dataFiles);

        List<String> inputFileSummaryRows =
                new ArrayList<>();

        for (File dataFile : dataFiles) {

            System.out.println(
                    "Loading: "
                            + dataFile.getName());

            final long[] fileMessageCount =
                    {0};

            FileLoadStatistics loadStatistics =
                    loader.loadFileWithStatistics(
                    dataFile.getAbsolutePath(),
                    msg -> {

                        statistics.accept(msg);
                        fileMessageCount[0]++;
                    });

            totalMessages +=
                    fileMessageCount[0];

            inputFileSummaryRows.add(
                    dataFile.getName()
                            + ","
                            + dataFile.length()
                            + ","
                            + loadStatistics.totalRows
                            + ","
                            + loadStatistics.exactDuplicateRows
                            + ","
                            + loadStatistics.targetMessages
                            + ","
                            + loadStatistics.invalidOrNonTargetRows);
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
                outputDir.resolve(
                        "type1_distance_loss.csv"),
                type1Results);

        exportCsv(
                outputDir.resolve(
                        "type5_distance_loss.csv"),
                type5Results);

        exportCsv(
                outputDir.resolve(
                        "type18_distance_loss.csv"),
                type18Results);

        exportDailyCsv(
                outputDir.resolve(
                        "type1_daily_distance_loss.csv"),
                statistics.getDailyResults(1));

        exportDailyCsv(
                outputDir.resolve(
                        "type5_daily_distance_loss.csv"),
                statistics.getDailyResults(5));

        exportDailyCsv(
                outputDir.resolve(
                        "type18_daily_distance_loss.csv"),
                statistics.getDailyResults(18));

        exportInputFileSummary(
                outputDir.resolve("input_file_summary.csv"),
                inputFileSummaryRows);
    }

    private static File[] removeCompressedCounterpartDuplicates(
            File[] files) {

        Set<String> names =
                new HashSet<>();

        for (File file : files) {
            names.add(file.getName().toLowerCase(Locale.ROOT));
        }

        List<File> filtered =
                new ArrayList<>();

        for (File file : files) {

            String lower =
                    file.getName().toLowerCase(Locale.ROOT);

            boolean uncompressed =
                    lower.endsWith(".ais")
                            || lower.endsWith(".csv");

            if (uncompressed && names.contains(lower + ".gz")) {
                System.out.println(
                        "Skipping compressed-counterpart duplicate: "
                                + file.getName());
                continue;
            }

            filtered.add(file);
        }

        return filtered.toArray(new File[0]);
    }

    private static boolean isSupportedInputFile(
            String name) {

        String lower =
                name.toLowerCase();

        return lower.endsWith(".ais")
                || lower.endsWith(".ais.gz")
                || lower.endsWith(".csv")
                || lower.endsWith(".csv.gz");
    }

    private static void exportInputFileSummary(
            Path filePath,
            List<String> rows) {

        try (PrintWriter writer =
                     new PrintWriter(filePath.toFile())) {

            writer.println(
                    "FILE_NAME,FILE_SIZE_BYTES,TOTAL_ROWS,EXACT_DUPLICATE_ROWS,TARGET_MESSAGES,INVALID_OR_NON_TARGET_ROWS");

            for (String row : rows) {
                writer.println(row);
            }

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
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
        Path filePath,
        List<VesselStatisticsResult> results) {

    try {

        PrintWriter writer =
                new PrintWriter(
                        filePath.toFile());

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
        Path filePath,
        List<DailyStatisticsResult> results) {

    try {

        PrintWriter writer =
                new PrintWriter(
                        filePath.toFile());

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
