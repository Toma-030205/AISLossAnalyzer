package ais.stats;

import ais.logic.DistanceCalculator;
import ais.logic.ReportRateTable;
import ais.model.AisMessage;
import ais.model.Vessel;

import java.time.Duration;
import java.util.List;

public class VesselStatistics {

    private static final double RECEIVER_LAT =
            34.718983358515715;

    private static final double RECEIVER_LON =
            135.29057866131427;

    private static final double MAX_DISTANCE_JUMP_KM =
            30.0;

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

            Double currentDistance =
                    getDistance(
                            targetType,
                            current,
                            latestLat,
                            latestLon);

            Double nextDistance =
                    getDistance(
                            targetType,
                            next,
                            latestLat,
                            latestLon);

            if (currentDistance != null
                    && nextDistance != null
                    && Math.abs(currentDistance - nextDistance)
                    > MAX_DISTANCE_JUMP_KM) {

                continue;
            }

            validMessageCount++;

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
        long lossCount =
                Math.max(
                        0,
                        (long) Math.ceil(estimatedLoss));

        totalLoss += lossCount;

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

            if (currentDistance != null) {

                result.addDistanceBin(
                        currentDistance,
                        lossCount);

                if (lossCount > 0) {

                    result.lossDistances.add(currentDistance);

                    result.lossCounts.add(lossCount);

                    if (currentDistance > maxDistance) {
                            maxDistance =currentDistance;
                    }
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

    private static Double getDistance(
            int targetType,
            AisMessage msg,
            Double latestLat,
            Double latestLon) {

        Double lat = null;
        Double lon = null;

        if ((targetType == 1 || targetType == 18)
                && msg.lat != null
                && msg.lon != null) {

            lat = msg.lat;
            lon = msg.lon;
        }

        if (targetType == 5) {

            if (msg.lat != null
                    && msg.lon != null) {

                lat = msg.lat;
                lon = msg.lon;

            } else if (latestLat != null
                    && latestLon != null) {

                lat = latestLat;
                lon = latestLon;
            }
        }

        if (lat == null || lon == null) {
            return null;
        }

        return DistanceCalculator.haversine(
                lat,
                lon,
                RECEIVER_LAT,
                RECEIVER_LON);
    }
}
