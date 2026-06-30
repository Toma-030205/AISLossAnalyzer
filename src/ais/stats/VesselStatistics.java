package ais.stats;

import ais.logic.AisAnalysisRules;
import ais.logic.DistanceCalculator;
import ais.logic.LossEstimator;
import ais.logic.ReportRateTracker;
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
        result.shipLength = vessel.getShipLength();

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

        AisMessage current = null;
        double currentExpectedDelta = -1.0;
        Double currentDistance = null;

        ReportRateTracker reportRateTracker =
                new ReportRateTracker();

        for (AisMessage message : messages) {

            if (isDynamicMessage(message)
                    && message.lat != null
                    && message.lon != null) {

                latestLat = message.lat;
                latestLon = message.lon;
            }

            if (!AisAnalysisRules.belongsToAnalysisType(
                    message.messageType,
                    targetType)) {

                continue;
            }

            Double nextDistance =
                    getDistance(
                            targetType,
                            message,
                            latestLat,
                            latestLon);

            double nextExpectedDelta =
                    reportRateTracker.accept(message);

            if (current == null) {
                current = message;
                currentExpectedDelta = nextExpectedDelta;
                currentDistance = nextDistance;
                continue;
            }

            double actualDeltaRaw =
                    Duration.between(
                            current.timestamp,
                            message.timestamp)
                            .toMillis() / 1000.0;

            boolean validInterval =
                    currentExpectedDelta > 0
                            && actualDeltaRaw >= 0
                            && actualDeltaRaw
                            < AisAnalysisRules
                                    .getTrackGapThresholdSeconds(
                                            targetType)
                            && !isDistanceJump(
                                    currentDistance,
                                    nextDistance);

            if (validInterval) {

                long lossCount =
                        LossEstimator.estimateMissingMessages(
                                actualDeltaRaw,
                                currentExpectedDelta);

                long actualDelta =
                        Math.round(actualDeltaRaw);

                validMessageCount++;
                totalLoss += lossCount;

                if (actualDelta > maxDelta) {
                    maxDelta = actualDelta;
                }

                deltaSum += actualDelta;
                deltaCount++;

                if (currentDistance != null) {

                    result.addDistanceBin(
                            currentDistance,
                            lossCount);

                    if (lossCount > 0) {

                        result.lossDistances.add(
                                currentDistance);

                        result.lossCounts.add(lossCount);

                        if (currentDistance > maxDistance) {
                            maxDistance = currentDistance;
                        }
                    }
                }
            }

            current = message;
            currentExpectedDelta = nextExpectedDelta;
            currentDistance = nextDistance;
        }

        result.totalMessages = validMessageCount;
        result.totalEstimatedLoss = totalLoss;
        result.maxDelta = maxDelta;

        if (deltaCount > 0) {
            result.averageDelta =
                    deltaSum / deltaCount;
        }

        result.maxDistance = maxDistance;

        double denominator =
                validMessageCount + totalLoss;

        if (denominator > 0) {
            result.lossRate =
                    ((double) totalLoss / denominator)
                            * 100.0;
        }

        return result;
    }

    private static boolean isDynamicMessage(AisMessage msg) {

        return msg.messageType == 1
                || msg.messageType == 2
                || msg.messageType == 3
                || msg.messageType == 18;
    }

    private static boolean isDistanceJump(
            Double currentDistance,
            Double nextDistance) {

        return currentDistance != null
                && nextDistance != null
                && Math.abs(currentDistance - nextDistance)
                > MAX_DISTANCE_JUMP_KM;
    }

    private static Double getDistance(
            int targetType,
            AisMessage msg,
            Double latestLat,
            Double latestLon) {

        Double lat = null;
        Double lon = null;

        if ((targetType == AisAnalysisRules.CLASS_A_POSITION
                || targetType == AisAnalysisRules.CLASS_B_POSITION)
                && msg.lat != null
                && msg.lon != null) {

            lat = msg.lat;
            lon = msg.lon;
        }

        if (targetType == AisAnalysisRules.STATIC_VOYAGE) {

            if (msg.lat != null && msg.lon != null) {
                lat = msg.lat;
                lon = msg.lon;
            } else if (latestLat != null && latestLon != null) {
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
