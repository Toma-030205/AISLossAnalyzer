package ais.stats;

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

        for (int i = 0;
             i < messages.size() - 1;
             i++) {

            AisMessage current =
                    messages.get(i);

            AisMessage next =
                    messages.get(i + 1);

            double actualDeltaRaw =
                    Duration.between(
                            current.timestamp,
                            next.timestamp
                    ).toMillis() / 1000.0;

            long actualDelta =
                    Math.round(actualDeltaRaw);

            long expectedDelta =
                    ReportRateTable
                            .getExpectedInterval(
                                    current);

            long estimatedLoss =
                    Math.round(
                            (double) actualDelta
                                    / expectedDelta
                    ) - 1;
        
                // Type1,2,3だけ距離計算
                if (current.lat != null
                        && current.lon != null) {

                double distance =
                        DistanceCalculator.haversine(
                                current.lat,
                                current.lon,
                                34.718983358515715,
                                135.29057866131427
                        );

                distanceSum += distance;

                        if (distance > maxDistance) {
                                maxDistance = distance;
                        }
                }

            // マイナス防止
            if (estimatedLoss < 0) {
                estimatedLoss = 0;
            }

            totalLoss += estimatedLoss;

            if (actualDelta > maxDelta) {
                maxDelta = actualDelta;
            }

            deltaSum += actualDelta;

            deltaCount++;
        }

        result.totalEstimatedLoss =
                totalLoss;

        result.maxDelta =
                maxDelta;

        result.averageDelta =
                deltaSum / deltaCount;

        result.averageDistance =
                distanceSum / deltaCount;

        result.maxDistance =
                maxDistance;

        // 欠落率
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