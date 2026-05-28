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

        result.shipLength =
                vessel.getShipLength();

        List<AisMessage> messages =
                vessel.getMessages();

        long totalLoss = 0;

        long maxDelta = 0;

        double deltaSum = 0;

        int deltaCount = 0;

        double maxDistance = 0;

        int validMessageCount = 0;
        Double latestLat = null;
        Double latestLon = null;

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
                if ((current.messageType == 1|| current.messageType == 2|| current.messageType == 3
                        || current.messageType == 18)

                        && current.lat != null
                        && current.lon != null) {

                        latestLat =current.lat;

                        latestLon =current.lon;
                }

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

            double expectedDelta =
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

        double estimatedLoss;

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

        // マイナスは0扱い
        if (estimatedLoss < 0) {
        estimatedLoss = 0;
        }

        // 欠落なしは除外
        if (estimatedLoss == 0) {
        continue;
        }

        totalLoss += Math.ceil(estimatedLoss);

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

            // Type1/18
            if ((targetType == 1 || targetType == 18)
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

            if (lat != null && lon != null && estimatedLoss > 0) {

                double distance = DistanceCalculator.haversine(
                            lat,
                            lon,
                            34.718983358515715,
                            135.29057866131427
                    );

                result.lossDistances.add(distance);

                result.lossCounts.add(Math.max(1,(long)Math.ceil(estimatedLoss)));

                if (distance > maxDistance) {
                        maxDistance =distance;
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
