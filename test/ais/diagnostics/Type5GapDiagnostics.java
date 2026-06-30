package ais.diagnostics;

import ais.logic.DistanceCalculator;
import ais.logic.LossEstimator;
import ais.model.AisMessage;
import ais.parser.FileLoader;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Type5GapDiagnostics {

    private static final double RECEIVER_LAT =
            34.718983358515715;

    private static final double RECEIVER_LON =
            135.29057866131427;

    private static final long TRACK_GAP_SECONDS = 3600;
    private static final double MAX_DISTANCE_JUMP_KM = 30.0;

    private static final int[] BUCKET_UPPER_BOUNDS = {
            360, 539, 720, 1079, 1439, 1799, 2159, 3599
    };

    private final Map<Integer, VesselState> vessels =
            new HashMap<>();

    private final long[] intervalHistogram =
            new long[(int) TRACK_GAP_SECONDS];

    private final Bucket[] buckets =
            new Bucket[BUCKET_UPPER_BOUNDS.length + 1];

    private long decodedMessages;
    private long type5Messages;
    private long consecutiveType5Intervals;
    private long negativeIntervals;
    private long trackGaps;
    private long trackGapHypotheticalLoss;
    private long distanceJumps;

    private Type5GapDiagnostics() {

        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new Bucket();
        }
    }

    public static void main(String[] args) {

        String dataDirectory =
                args.length > 0
                        ? args[0]
                        : "C:/Users/Owner/AISData";

        Type5GapDiagnostics diagnostics =
                new Type5GapDiagnostics();

        diagnostics.load(dataDirectory);
        diagnostics.printReport();
    }

    private void load(String dataDirectory) {

        File[] files =
                new File(dataDirectory)
                        .listFiles((dir, name) ->
                                name.toLowerCase().endsWith(".ais"));

        if (files == null || files.length == 0) {
            throw new IllegalArgumentException(
                    "AIS files not found: " + dataDirectory);
        }

        Arrays.sort(files, (left, right) ->
                left.getName().compareTo(right.getName()));

        FileLoader loader = new FileLoader();

        for (File file : files) {
            System.out.println("Loading: " + file.getName());
            loader.loadFile(file.getAbsolutePath(), this::accept);
        }
    }

    private void accept(AisMessage message) {

        decodedMessages++;

        VesselState state =
                vessels.computeIfAbsent(
                        message.mmsi,
                        ignored -> new VesselState());

        if (isDynamicMessage(message)
                && message.lat != null
                && message.lon != null) {

            state.latestDistance =
                    DistanceCalculator.haversine(
                            message.lat,
                            message.lon,
                            RECEIVER_LAT,
                            RECEIVER_LON);
        }

        processOldInterval(state, message);

        if (message.messageType == 5) {
            type5Messages++;
            processType5Interval(state, message);
        }

        state.previousAnyType = message.messageType;
        state.previousAnyTime = message.timestamp;
        state.previousAnyDistance = state.latestDistance;
    }

    private void processOldInterval(
            VesselState state,
            AisMessage nextMessage) {

        if (state.previousAnyType != 5
                || state.previousAnyTime == null) {

            return;
        }

        long seconds = roundedSecondsBetween(
                state.previousAnyTime,
                nextMessage.timestamp);

        if (seconds < 0 || seconds > TRACK_GAP_SECONDS) {
            return;
        }

        long loss = ceilLoss(seconds);

        state.oldValidIntervals++;
        state.oldLoss += loss;

        if (state.previousAnyDistance != null) {
            state.oldDistanceIntervals++;
            state.oldDistanceLoss += loss;
        }
    }

    private void processType5Interval(
            VesselState state,
            AisMessage currentType5) {

        if (state.previousType5Time == null) {
            state.previousType5Time = currentType5.timestamp;
            state.previousType5Distance = state.latestDistance;
            return;
        }

        consecutiveType5Intervals++;

        double actualSeconds = secondsBetween(
                state.previousType5Time,
                currentType5.timestamp);

        long seconds = Math.round(actualSeconds);

        if (actualSeconds < 0) {
            negativeIntervals++;
        } else {
            recordBucket(seconds, actualSeconds);

            if (actualSeconds >= TRACK_GAP_SECONDS) {
                trackGaps++;
                trackGapHypotheticalLoss += ceilLoss(seconds);
            } else {

                if (seconds >= 0
                        && seconds < intervalHistogram.length) {

                    intervalHistogram[(int) seconds]++;
                }

                boolean distanceJump =
                        state.previousType5Distance != null
                                && state.latestDistance != null
                                && Math.abs(
                                        state.previousType5Distance
                                                - state.latestDistance)
                                > MAX_DISTANCE_JUMP_KM;

                if (distanceJump) {
                    distanceJumps++;
                } else {
                    long ceilLoss = ceilLoss(seconds);
                    long roundedLoss = correctedLoss(actualSeconds);

                    state.newValidIntervals++;
                    state.newCeilLoss += ceilLoss;
                    state.newRoundedLoss += roundedLoss;

                    if (state.previousType5Distance != null) {
                        state.newDistanceIntervals++;
                        state.newDistanceCeilLoss += ceilLoss;
                        state.newDistanceRoundedLoss += roundedLoss;
                    }
                }
            }
        }

        state.previousType5Time = currentType5.timestamp;
        state.previousType5Distance = state.latestDistance;
    }

    private void recordBucket(
            long seconds,
            double actualSeconds) {

        int bucketIndex = BUCKET_UPPER_BOUNDS.length;

        for (int i = 0; i < BUCKET_UPPER_BOUNDS.length; i++) {
            if (seconds <= BUCKET_UPPER_BOUNDS[i]) {
                bucketIndex = i;
                break;
            }
        }

        Bucket bucket = buckets[bucketIndex];
        bucket.intervals++;

        if (actualSeconds < TRACK_GAP_SECONDS) {
            bucket.ceilLoss += ceilLoss(seconds);
            bucket.roundedLoss += correctedLoss(actualSeconds);
        }
    }

    private void printReport() {

        Aggregate oldAll = new Aggregate();
        Aggregate newAll = new Aggregate();
        Aggregate oldMin10 = new Aggregate();
        Aggregate newMin10 = new Aggregate();

        for (VesselState state : vessels.values()) {

            oldAll.addOld(state);
            newAll.addNew(state);

            if (state.oldValidIntervals >= 10) {
                oldMin10.addOld(state);
            }

            if (state.newValidIntervals >= 10) {
                newMin10.addNew(state);
            }
        }

        System.out.println();
        System.out.println("===== TYPE 5 GAP DIAGNOSTICS =====");
        System.out.println("Decoded target messages: " + decodedMessages);
        System.out.println("Type 5 messages: " + type5Messages);
        System.out.println("Consecutive Type 5 intervals: "
                + consecutiveType5Intervals);
        System.out.println("Negative intervals: " + negativeIntervals);
        System.out.println("Excluded at >=3600 seconds: " + trackGaps);
        System.out.println("Hypothetical loss in excluded track gaps: "
                + trackGapHypotheticalLoss);
        System.out.println("Excluded by >30 km distance jump: "
                + distanceJumps);

        System.out.println();
        System.out.println("Old immediate-next-message method (all):");
        oldAll.print(false);

        System.out.println();
        System.out.println("Old immediate-next-message method (min 10):");
        oldMin10.print(false);

        System.out.println();
        System.out.println("Next-Type-5 comparison (all):");
        newAll.print(true);

        System.out.println();
        System.out.println("Next-Type-5 comparison (min 10):");
        newMin10.print(true);

        System.out.println();
        System.out.println("Intervals by gap bucket:");

        int lower = 0;
        for (int i = 0; i < buckets.length; i++) {
            String label;

            if (i < BUCKET_UPPER_BOUNDS.length) {
                label = lower + "-" + BUCKET_UPPER_BOUNDS[i];
                lower = BUCKET_UPPER_BOUNDS[i] + 1;
            } else {
                label = TRACK_GAP_SECONDS + "+";
            }

            Bucket bucket = buckets[i];
            System.out.printf(
                    "%10s sec: intervals=%d legacyCeilLoss=%d correctedLoss=%d%n",
                    label,
                    bucket.intervals,
                    bucket.ceilLoss,
                    bucket.roundedLoss);
        }

        long histogramTotal = Arrays.stream(intervalHistogram).sum();

        System.out.println();
        System.out.println("Percentiles below 3600 seconds:");
        System.out.println("p50=" + percentile(histogramTotal, 0.50));
        System.out.println("p75=" + percentile(histogramTotal, 0.75));
        System.out.println("p90=" + percentile(histogramTotal, 0.90));
        System.out.println("p95=" + percentile(histogramTotal, 0.95));
        System.out.println("p99=" + percentile(histogramTotal, 0.99));

        System.out.println();
        System.out.println("Intervals around the nominal 360 seconds:");

        for (int seconds = 350; seconds <= 370; seconds++) {
            System.out.printf(
                    "%d sec: %d%n",
                    seconds,
                    intervalHistogram[seconds]);
        }
    }

    private long percentile(long total, double fraction) {

        long target = (long) Math.ceil(total * fraction);
        long cumulative = 0;

        for (int seconds = 0;
             seconds < intervalHistogram.length;
             seconds++) {

            cumulative += intervalHistogram[seconds];

            if (cumulative >= target) {
                return seconds;
            }
        }

        return -1;
    }

    private static double secondsBetween(
            LocalDateTime start,
            LocalDateTime end) {

        return Duration.between(start, end).toMillis()
                / 1000.0;
    }

    private static long roundedSecondsBetween(
            LocalDateTime start,
            LocalDateTime end) {

        return Math.round(secondsBetween(start, end));
    }

    private static long ceilLoss(long seconds) {

        return Math.max(
                0,
                (long) Math.ceil(seconds / 360.0 - 1));
    }

    private static long correctedLoss(double seconds) {

        return LossEstimator.estimateMissingMessages(
                seconds,
                360.0);
    }

    private static boolean isDynamicMessage(AisMessage message) {

        return message.messageType == 1
                || message.messageType == 2
                || message.messageType == 3
                || message.messageType == 18;
    }

    private static class VesselState {

        int previousAnyType = -1;
        LocalDateTime previousAnyTime;
        Double previousAnyDistance;

        LocalDateTime previousType5Time;
        Double previousType5Distance;
        Double latestDistance;

        long oldValidIntervals;
        long oldLoss;
        long oldDistanceIntervals;
        long oldDistanceLoss;

        long newValidIntervals;
        long newCeilLoss;
        long newRoundedLoss;
        long newDistanceIntervals;
        long newDistanceCeilLoss;
        long newDistanceRoundedLoss;
    }

    private static class Aggregate {

        long vessels;
        long validIntervals;
        long loss;
        long roundedLoss;
        long distanceIntervals;
        long distanceLoss;
        long distanceRoundedLoss;

        void addOld(VesselState state) {

            if (state.oldValidIntervals > 0) {
                vessels++;
            }

            validIntervals += state.oldValidIntervals;
            loss += state.oldLoss;
            distanceIntervals += state.oldDistanceIntervals;
            distanceLoss += state.oldDistanceLoss;
        }

        void addNew(VesselState state) {

            if (state.newValidIntervals > 0) {
                vessels++;
            }

            validIntervals += state.newValidIntervals;
            loss += state.newCeilLoss;
            roundedLoss += state.newRoundedLoss;
            distanceIntervals += state.newDistanceIntervals;
            distanceLoss += state.newDistanceCeilLoss;
            distanceRoundedLoss += state.newDistanceRoundedLoss;
        }

        void print(boolean includeRounded) {

            System.out.println("vessels=" + vessels);
            System.out.println("validIntervals=" + validIntervals);
            System.out.println("legacyCeilLoss=" + loss);
            System.out.println("distanceIntervals=" + distanceIntervals);
            System.out.println("distanceCeilLoss=" + distanceLoss);

            if (includeRounded) {
                System.out.println("correctedLoss=" + roundedLoss);
                System.out.println("distanceCorrectedLoss="
                        + distanceRoundedLoss);
            }
        }
    }

    private static class Bucket {

        long intervals;
        long ceilLoss;
        long roundedLoss;
    }
}
