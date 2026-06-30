package ais.stats;

import ais.logic.AisAnalysisRules;
import ais.logic.DistanceCalculator;
import ais.logic.LossEstimator;
import ais.logic.ReportRateTracker;
import ais.model.AisMessage;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StreamingVesselStatistics {

    private static final double RECEIVER_LAT =
            34.718983358515715;

    private static final double RECEIVER_LON =
            135.29057866131427;

    private static final double MAX_DISTANCE_JUMP_KM =
            30.0;

    private final Map<Integer, VesselState> vessels =
            new HashMap<>();

    public void accept(AisMessage msg) {

        if (!isTargetMessage(msg)) {
            return;
        }

        VesselState state =
                vessels.get(msg.mmsi);

        if (state == null) {

            state =
                    new VesselState(msg.mmsi);

            vessels.put(
                    msg.mmsi,
                    state);
        }

        state.accept(msg);
    }

    public List<VesselStatisticsResult> getResults(
            int targetType,
            int minMessages) {

        List<VesselStatisticsResult> results =
                new ArrayList<>();

        for (VesselState state : vessels.values()) {

            VesselStatisticsResult result =
                    state.getTypeState(targetType)
                            .toResult(
                                    state.mmsi,
                                    state.shipLength);

            if (result.totalMessages < minMessages) {
                continue;
            }

            results.add(result);
        }

        return results;
    }

    public int getVesselCount() {

        return vessels.size();
    }

    public List<DailyStatisticsResult> getDailyResults(
            int targetType) {

        List<DailyStatisticsResult> results =
                new ArrayList<>();

        for (VesselState state : vessels.values()) {

            Map<LocalDate, TypeState> dailyMap =
                    state.getDailyTypeStates(
                            targetType);

            for (Map.Entry<LocalDate, TypeState> entry
                    : dailyMap.entrySet()) {

                VesselStatisticsResult result =
                        entry.getValue()
                                .toResult(
                                        state.mmsi,
                                        state.shipLength);

                if (result.totalMessages == 0) {
                    continue;
                }

                results.add(
                        new DailyStatisticsResult(
                                entry.getKey(),
                                targetType,
                                state.mmsi,
                                state.shipLength,
                                result));
            }
        }

        results.sort(
                Comparator
                        .comparing((DailyStatisticsResult r) -> r.date)
                        .thenComparingInt(r -> r.messageType)
                        .thenComparingInt(r -> r.mmsi));

        return results;
    }

    private static boolean isTargetMessage(AisMessage msg) {

        return AisAnalysisRules.getAnalysisType(
                msg.messageType) > 0;
    }

    private static boolean isDynamicMessage(AisMessage msg) {

        return msg.messageType == 1
                || msg.messageType == 2
                || msg.messageType == 3
                || msg.messageType == 18;
    }

    private static class VesselState {

        final int mmsi;

        Integer shipLength;

        Double latestLat;

        Double latestLon;

        final Map<Integer, TypeState> typeStates =
                new HashMap<>();

        final Map<Integer, Map<LocalDate, TypeState>> dailyTypeStates =
                new HashMap<>();

        final Map<Integer, IntervalCursor> intervalCursors =
                new HashMap<>();

        VesselState(int mmsi) {

            this.mmsi = mmsi;
            typeStates.put(1, new TypeState());
            typeStates.put(5, new TypeState());
            typeStates.put(18, new TypeState());
            dailyTypeStates.put(1, new HashMap<>());
            dailyTypeStates.put(5, new HashMap<>());
            dailyTypeStates.put(18, new HashMap<>());
            intervalCursors.put(1, new IntervalCursor());
            intervalCursors.put(5, new IntervalCursor());
            intervalCursors.put(18, new IntervalCursor());
        }

        void accept(AisMessage msg) {

            if (msg.shipLength != null
                    && msg.shipLength > 0
                    && shipLength == null) {

                shipLength = msg.shipLength;
            }

            if (isDynamicMessage(msg)
                    && msg.lat != null
                    && msg.lon != null) {

                latestLat = msg.lat;
                latestLon = msg.lon;
            }

            int targetType =
                    AisAnalysisRules.getAnalysisType(
                            msg.messageType);

            if (targetType < 0) {
                return;
            }

            IntervalCursor cursor =
                    intervalCursors.get(targetType);

            double expectedDelta =
                    cursor.reportRateTracker.accept(msg);

            Double distance =
                    getDistance(
                            targetType,
                            msg,
                            latestLat,
                            latestLon);

            if (cursor.current != null) {
                processInterval(
                        targetType,
                        cursor.current,
                        msg,
                        cursor.currentExpectedDelta,
                        cursor.currentDistance,
                        distance);
            }

            cursor.current = msg;
            cursor.currentExpectedDelta = expectedDelta;
            cursor.currentDistance = distance;
        }

        TypeState getTypeState(int targetType) {

            return typeStates.get(targetType);
        }

        Map<LocalDate, TypeState> getDailyTypeStates(
                int targetType) {

            return dailyTypeStates.get(targetType);
        }

        private void processInterval(
                int targetType,
                AisMessage currentMsg,
                AisMessage nextMsg,
                double expectedDelta,
                Double currentDistance,
                Double nextDistance) {

            TypeState typeState =
                    typeStates.get(targetType);

            if (expectedDelta <= 0) {
                return;
            }

            double actualDeltaRaw =
                    Duration.between(
                            currentMsg.timestamp,
                            nextMsg.timestamp)
                            .toMillis() / 1000.0;

            if (actualDeltaRaw < 0) {
                return;
            }

            if (actualDeltaRaw
                    >= AisAnalysisRules
                            .getTrackGapThresholdSeconds(targetType)) {

                return;
            }

            if (currentDistance != null
                    && nextDistance != null
                    && Math.abs(currentDistance - nextDistance)
                    > MAX_DISTANCE_JUMP_KM) {

                return;
            }

            TypeState dailyTypeState =
                    getOrCreateDailyTypeState(
                            targetType,
                            currentMsg.timestamp.toLocalDate());

            long lossCount =
                    LossEstimator.estimateMissingMessages(
                            actualDeltaRaw,
                            expectedDelta);

            long actualDelta =
                    Math.round(actualDeltaRaw);

            addInterval(
                    typeState,
                    actualDelta,
                    lossCount,
                    currentDistance);

            addInterval(
                    dailyTypeState,
                    actualDelta,
                    lossCount,
                    currentDistance);
        }

        private TypeState getOrCreateDailyTypeState(
                int targetType,
                LocalDate date) {

            Map<LocalDate, TypeState> dailyMap =
                    dailyTypeStates.get(targetType);

            TypeState typeState =
                    dailyMap.get(date);

            if (typeState == null) {

                typeState =
                        new TypeState();

                dailyMap.put(
                        date,
                        typeState);
            }

            return typeState;
        }

        private void addInterval(
                TypeState typeState,
                long actualDelta,
                long lossCount,
                Double currentDistance) {

            typeState.totalMessages++;
            typeState.totalLoss += lossCount;

            if (actualDelta > typeState.maxDelta) {
                typeState.maxDelta = actualDelta;
            }

            typeState.deltaSum += actualDelta;
            typeState.deltaCount++;

            if (currentDistance == null) {
                return;
            }

            typeState.result.addDistanceBin(
                    currentDistance,
                    lossCount);

            if (lossCount > 0) {

                typeState.result.lossDistances.add(
                        currentDistance);

                typeState.result.lossCounts.add(
                        lossCount);

                if (currentDistance > typeState.maxDistance) {
                    typeState.maxDistance = currentDistance;
                }
            }
        }
    }

    private static class TypeState {

        final VesselStatisticsResult result =
                new VesselStatisticsResult();

        int totalMessages;

        long totalLoss;

        long maxDelta;

        double deltaSum;

        int deltaCount;

        double maxDistance;

        VesselStatisticsResult toResult(
                int mmsi,
                Integer shipLength) {

            result.mmsi = mmsi;
            result.shipLength = shipLength;
            result.totalMessages = totalMessages;
            result.totalEstimatedLoss = totalLoss;
            result.maxDelta = maxDelta;
            result.maxDistance = maxDistance;

            if (deltaCount > 0) {
                result.averageDelta =
                        deltaSum / deltaCount;
            }

            double denominator =
                    totalMessages + totalLoss;

            if (denominator > 0) {
                result.lossRate =
                        ((double) totalLoss / denominator)
                                * 100.0;
            }

            return result;
        }
    }

    private static class IntervalCursor {

        AisMessage current;

        double currentExpectedDelta;

        Double currentDistance;

        final ReportRateTracker reportRateTracker =
                new ReportRateTracker();
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
