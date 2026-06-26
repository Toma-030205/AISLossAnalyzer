package ais.stats;

import ais.logic.DistanceCalculator;
import ais.logic.ReportRateTable;
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

    private static final long MAX_INTERVAL_SECONDS =
            Long.getLong(
                    "ais.maxIntervalSeconds",
                    3600L);

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

        return msg.messageType == 1
                || msg.messageType == 2
                || msg.messageType == 3
                || msg.messageType == 5
                || msg.messageType == 18;
    }

    private static boolean isDynamicMessage(AisMessage msg) {

        return msg.messageType == 1
                || msg.messageType == 2
                || msg.messageType == 3
                || msg.messageType == 18;
    }

    private static boolean isAnalyzedType(int messageType) {

        return messageType == 1
                || messageType == 5
                || messageType == 18;
    }

    private static class VesselState {

        final int mmsi;

        Integer shipLength;

        AisMessage previous;

        AisMessage current;

        Double latestLat;

        Double latestLon;

        final Map<Integer, TypeState> typeStates =
                new HashMap<>();

        final Map<Integer, Map<LocalDate, TypeState>> dailyTypeStates =
                new HashMap<>();

        VesselState(int mmsi) {

            this.mmsi = mmsi;
            typeStates.put(1, new TypeState());
            typeStates.put(5, new TypeState());
            typeStates.put(18, new TypeState());
            dailyTypeStates.put(1, new HashMap<>());
            dailyTypeStates.put(5, new HashMap<>());
            dailyTypeStates.put(18, new HashMap<>());
        }

        void accept(AisMessage msg) {

            if (msg.shipLength != null
                    && msg.shipLength > 0
                    && shipLength == null) {

                shipLength = msg.shipLength;
            }

            if (current != null) {
                processInterval(current, msg, previous);
            }

            previous = current;
            current = msg;
        }

        TypeState getTypeState(int targetType) {

            return typeStates.get(targetType);
        }

        Map<LocalDate, TypeState> getDailyTypeStates(
                int targetType) {

            return dailyTypeStates.get(targetType);
        }

        private void processInterval(
                AisMessage currentMsg,
                AisMessage nextMsg,
                AisMessage previousMsg) {

            if (isDynamicMessage(currentMsg)
                    && currentMsg.lat != null
                    && currentMsg.lon != null) {

                latestLat = currentMsg.lat;
                latestLon = currentMsg.lon;
            }

            if (!isAnalyzedType(currentMsg.messageType)) {
                return;
            }

            int targetType =
                    currentMsg.messageType;

            TypeState typeState =
                    typeStates.get(targetType);

            TypeState dailyTypeState =
                    getOrCreateDailyTypeState(
                            targetType,
                            currentMsg.timestamp.toLocalDate());

            double expectedDelta =
                    ReportRateTable.getExpectedInterval(
                            currentMsg,
                            previousMsg);

            if (expectedDelta <= 0) {
                return;
            }

            double actualDeltaRaw =
                    Duration.between(
                            currentMsg.timestamp,
                            nextMsg.timestamp)
                            .toMillis() / 1000.0;

            long actualDelta =
                    Math.round(actualDeltaRaw);

            if (actualDelta > MAX_INTERVAL_SECONDS) {
                return;
            }

            Double currentDistance =
                    getDistance(
                            targetType,
                            currentMsg,
                            latestLat,
                            latestLon);

            Double nextDistance =
                    getDistance(
                            targetType,
                            nextMsg,
                            latestLat,
                            latestLon);

            if (currentDistance != null
                    && nextDistance != null
                    && Math.abs(currentDistance - nextDistance)
                    > MAX_DISTANCE_JUMP_KM) {

                return;
            }

            double estimatedLoss;

            if (targetType == 5) {

                estimatedLoss =
                        (actualDelta / expectedDelta) - 1;

            } else {

                estimatedLoss =
                        Math.round(
                                (double) actualDelta
                                        / expectedDelta) - 1;
            }

            if (estimatedLoss < 0) {
                estimatedLoss = 0;
            }

            long lossCount =
                    Math.max(
                            0,
                            (long) Math.ceil(estimatedLoss));

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
