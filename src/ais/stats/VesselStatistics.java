package ais.stats;

import ais.logic.DistanceCalculator;
import ais.logic.ReportRateTable;
import ais.model.AisMessage;
import ais.model.Vessel;

import java.time.Duration;
import java.util.List;

public class VesselStatistics {

    public VesselStatisticsResult analyze(
            Vessel vessel) {

        VesselStatisticsResult result =
                new VesselStatisticsResult();

        result.mmsi = vessel.getMmsi();

        List<AisMessage> messages =
                vessel.getMessages();

        result.totalMessages =
                messages.size();

        if (messages.size() < 2) {
            return result;
        }

        long totalLoss = 0;

        long maxDelta = 0;

        double deltaSum = 0;

        int deltaCount = 0;

        double distanceSum = 0;

        double maxDistance = 0;

        int distanceCount = 0;

        for (int i = 0;
             i < messages.size() - 1;
             i++) {

            AisMessage current =
                    messages.get(i);

            AisMessage next =
                    messages.get(i + 1);

            // =====================================
            // Type1 と Type5 のみ解析対象
            // =====================================

            if (current.messageType != 1
                    && current.messageType != 5) {

                continue;
            }

            // =====================================
            // 実際の送信間隔
            // =====================================

            double actualDeltaRaw =
                    Duration.between(
                            current.timestamp,
                            next.timestamp
                    ).toMillis() / 1000.0;

            long actualDelta =
                    Math.round(actualDeltaRaw);

            // =====================================
            // 想定送信間隔
            // =====================================

            AisMessage prev = null;

            if (i > 0) {
                prev = messages.get(i - 1);
            }

            long expectedDelta =
                    ReportRateTable
                            .getExpectedInterval(
                                    current,
                                    prev);

            // 不正防止
            if (expectedDelta <= 0) {
                continue;
            }

            // =====================================
            // 欠落数推定
            // =====================================

            long estimatedLoss;

            // Type5は切り捨ての方が安定
            if (current.messageType == 5) {

                estimatedLoss =
                        (actualDelta / expectedDelta)
                                - 1;

            } else {

                estimatedLoss =
                        Math.round(
                                (double) actualDelta
                                        / expectedDelta
                        ) - 1;
            }

            // マイナス防止
            if (estimatedLoss < 0) {
                estimatedLoss = 0;
            }

            totalLoss += estimatedLoss;

            // =====================================
            // 最大間隔
            // =====================================

            if (actualDelta > maxDelta) {
                maxDelta = actualDelta;
            }

            // =====================================
            // 平均間隔
            // =====================================

            deltaSum += actualDelta;

            deltaCount++;

            // =====================================
            // 距離計算
            // Type1のみ
            // =====================================

            if (current.messageType == 1
                    && current.lat != null
                    && current.lon != null) {

                double distance =
                        DistanceCalculator.haversine(
                                current.lat,
                                current.lon,
                                34.718983358515715,
                                135.29057866131427
                        );

                distanceSum += distance;

                distanceCount++;

                if (distance > maxDistance) {
                    maxDistance = distance;
                }
            }
        }

        // =====================================
        // 結果格納
        // =====================================

        result.totalEstimatedLoss =
                totalLoss;

        result.maxDelta =
                maxDelta;

        if (deltaCount > 0) {

            result.averageDelta =
                    deltaSum / deltaCount;
        }

        if (distanceCount > 0) {

            result.averageDistance =
                    distanceSum / distanceCount;
        }

        result.maxDistance =
                maxDistance;

        // =====================================
        // 欠落率
        // =====================================

        double denominator =
                result.totalMessages
                        + totalLoss;

        if (denominator > 0) {

            result.lossRate =
                    ((double) totalLoss
                            / denominator)
                            * 100.0;
        }

        return result;
    }
}