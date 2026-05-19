package ais.stats;

import ais.logic.DistanceCalculator;
import ais.logic.ReportRateTable;
import ais.model.AisMessage;
import ais.model.Vessel;

import java.time.Duration;
import java.util.List;

public class VesselStatistics {

    public VesselStatisticsResult analyze(
            Vessel vessel,
            int targetType) {

        VesselStatisticsResult result =
                new VesselStatisticsResult();

        result.mmsi = vessel.getMmsi();

        List<AisMessage> messages =
                vessel.getMessages();

        long totalLoss = 0;

        long maxDelta = 0;

        double deltaSum = 0;

        int deltaCount = 0;

        double distanceSum = 0;

        double maxDistance = 0;

        int distanceCount = 0;

        // =====================================
        // Type1/2/3 の最新位置を保持
        // =====================================

        Double latestLat = null;
        Double latestLon = null;

        for (AisMessage msg : messages) {

            if ((msg.messageType == 1
                    || msg.messageType == 2
                    || msg.messageType == 3)
                    && msg.lat != null
                    && msg.lon != null) {

                latestLat = msg.lat;
                latestLon = msg.lon;
            }
        }

        int validMessageCount = 0;

        // =====================================
        // メイン解析
        // =====================================

        for (int i = 0;
             i < messages.size() - 1;
             i++) {

            AisMessage current =
                    messages.get(i);

            AisMessage next =
                    messages.get(i + 1);

            // =====================================
            // 対象Typeのみ解析
            // =====================================

            if (current.messageType != targetType) {
                continue;
            }

            validMessageCount++;

            // =====================================
            // 実際間隔
            // =====================================

            double actualDeltaRaw =
                    Duration.between(
                            current.timestamp,
                            next.timestamp
                    ).toMillis() / 1000.0;

            long actualDelta =
                    Math.round(actualDeltaRaw);

            // =====================================
            // 想定間隔
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

            if (expectedDelta <= 0) {
                continue;
            }

            // =====================================
            // 欠落推定
            // =====================================

            long estimatedLoss;

            if (targetType == 5) {

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
            // =====================================

            Double lat = null;
            Double lon = null;

            // Type1/2/3
            if ((targetType == 1)
                    && current.lat != null
                    && current.lon != null) {

                lat = current.lat;
                lon = current.lon;
            }

            // Type5
            if (targetType == 5
                    && latestLat != null
                    && latestLon != null) {

                lat = latestLat;
                lon = latestLon;
            }

            if (lat != null && lon != null) {

                double distance =
                        DistanceCalculator.haversine(
                                lat,
                                lon,
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

        result.totalMessages =
                validMessageCount;

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

        } else {

            result.averageDistance =
                    Double.NaN;
        }

        result.maxDistance =
                maxDistance;

        double denominator =
                validMessageCount
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