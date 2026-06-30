package ais.diagnostics;

import ais.logic.AisAnalysisRules;
import ais.logic.LossEstimator;
import ais.logic.ReportRateTracker;
import ais.model.AisMessage;
import ais.parser.FileLoader;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class MessageGapDiagnostics {

    private final Map<Integer, VesselState> vessels =
            new HashMap<>();

    private final Map<Integer, TypeSummary> summaries =
            new HashMap<>();

    private long decodedMessages;

    private MessageGapDiagnostics() {

        summaries.put(1, new TypeSummary(1));
        summaries.put(5, new TypeSummary(5));
        summaries.put(18, new TypeSummary(18));
    }

    public static void main(String[] args) {

        String dataDirectory =
                args.length > 0
                        ? args[0]
                        : "C:/Users/Owner/AISData";

        MessageGapDiagnostics diagnostics =
                new MessageGapDiagnostics();

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

        int type =
                AisAnalysisRules.getAnalysisType(
                        message.messageType);

        VesselState vessel =
                vessels.computeIfAbsent(
                        message.mmsi,
                        ignored -> new VesselState());

        Cursor cursor = vessel.cursors.get(type);
        TypeSummary summary = summaries.get(type);

        summary.messages++;
        summary.messageTypeCounts.merge(
                message.messageType,
                1L,
                Long::sum);

        if (type == 18) {
            summary.recordClassBUnit(message);
        }

        double nextExpected = cursor.tracker.accept(message);

        if (cursor.current != null) {
            analyzeInterval(
                    summary,
                    cursor.current,
                    message,
                    cursor.currentExpected);
        }

        cursor.current = message;
        cursor.currentExpected = nextExpected;
    }

    private void analyzeInterval(
            TypeSummary summary,
            AisMessage current,
            AisMessage next,
            double expectedSeconds) {

        summary.intervals++;

        double actualSeconds =
                Duration.between(
                        current.timestamp,
                        next.timestamp)
                        .toMillis() / 1000.0;

        if (actualSeconds < 0) {
            summary.negativeIntervals++;
            return;
        }

        if (actualSeconds == 0) {
            summary.exactZeroIntervals++;
        }

        if (actualSeconds < 0.5) {
            summary.subHalfSecondIntervals++;
        }

        if (actualSeconds
                >= AisAnalysisRules
                        .getTrackGapThresholdSeconds(summary.type)) {

            summary.trackGaps++;
            return;
        }

        if (expectedSeconds <= 0) {
            summary.invalidExpected++;
            return;
        }

        summary.validIntervals++;
        summary.expectedCounts.merge(
                expectedSeconds,
                1L,
                Long::sum);

        long productionLoss =
                LossEstimator.estimateMissingMessages(
                        actualSeconds,
                        expectedSeconds);

        long integerSecondLoss =
                LossEstimator.estimateMissingMessages(
                        Math.round(actualSeconds),
                        expectedSeconds);

        summary.productionLoss += productionLoss;
        summary.integerSecondLoss += integerSecondLoss;

        if (summary.type == 18) {
            summary.recordClassBInterval(
                    current,
                    actualSeconds,
                    productionLoss);
        }

        if (productionLoss != integerSecondLoss) {
            summary.integerRoundingChangedIntervals++;
        }

        if (summary.type == 5) {

            long legacyCeilLoss =
                    Math.max(
                            0,
                            (long) Math.ceil(
                                    Math.round(actualSeconds)
                                            / expectedSeconds - 1));

            summary.legacyType5CeilLoss += legacyCeilLoss;
        }
    }

    private void printReport() {

        System.out.println();
        System.out.println("===== MESSAGE GAP DIAGNOSTICS =====");
        System.out.println("Decoded target messages: " + decodedMessages);

        for (int type : new int[] {1, 5, 18}) {
            summaries.get(type).print();
        }
    }

    private static class VesselState {

        final Map<Integer, Cursor> cursors = new HashMap<>();

        VesselState() {
            cursors.put(1, new Cursor());
            cursors.put(5, new Cursor());
            cursors.put(18, new Cursor());
        }
    }

    private static class Cursor {

        AisMessage current;
        double currentExpected;

        final ReportRateTracker tracker =
                new ReportRateTracker();
    }

    private static class TypeSummary {

        final int type;

        final Map<Integer, Long> messageTypeCounts =
                new TreeMap<>();

        final Map<Double, Long> expectedCounts =
                new TreeMap<>();

        long messages;
        long intervals;
        long negativeIntervals;
        long exactZeroIntervals;
        long subHalfSecondIntervals;
        long trackGaps;
        long invalidExpected;
        long validIntervals;
        long productionLoss;
        long integerSecondLoss;
        long integerRoundingChangedIntervals;
        long legacyType5CeilLoss;

        long classBSoMessages;
        long classBCsMessages;
        long classBUnknownMessages;
        long classBAssignedMessages;

        final Map<String, IntervalGroup> classBIntervalGroups =
                new TreeMap<>();

        TypeSummary(int type) {
            this.type = type;
        }

        void recordClassBUnit(AisMessage message) {

            if (message.classBCsUnit == null) {
                classBUnknownMessages++;
            } else if (message.classBCsUnit) {
                classBCsMessages++;
            } else {
                classBSoMessages++;
            }

            if (Boolean.TRUE.equals(message.assignedMode)) {
                classBAssignedMessages++;
            }
        }

        void recordClassBInterval(
                AisMessage message,
                double actualSeconds,
                long loss) {

            String unit =
                    Boolean.TRUE.equals(message.classBCsUnit)
                            ? "CS"
                            : "SO";

            String speed;

            if (message.sog == null || message.sog <= 2.0) {
                speed = "0-2";
            } else if (message.sog <= 14.0) {
                speed = "2-14";
            } else {
                speed = ">14";
            }

            classBIntervalGroups
                    .computeIfAbsent(
                            unit + " " + speed,
                            ignored -> new IntervalGroup())
                    .add(actualSeconds, loss);
        }

        void print() {

            System.out.println();
            System.out.println("Type " + type + ":");
            System.out.println("messages=" + messages);
            System.out.println("messageTypeCounts=" + messageTypeCounts);
            System.out.println("intervals=" + intervals);
            System.out.println("negativeIntervals=" + negativeIntervals);
            System.out.println("exactZeroIntervals=" + exactZeroIntervals);
            System.out.println("subHalfSecondIntervals="
                    + subHalfSecondIntervals);
            System.out.println("trackGaps=" + trackGaps);
            System.out.println("validIntervals=" + validIntervals);
            System.out.println("productionLoss=" + productionLoss);
            System.out.println("integerSecondLoss=" + integerSecondLoss);
            System.out.println("integerRoundingChangedIntervals="
                    + integerRoundingChangedIntervals);
            System.out.println("expectedCounts=" + expectedCounts);

            if (type == 5) {
                System.out.println("legacyType5CeilLoss="
                        + legacyType5CeilLoss);
            }

            if (type == 18) {
                System.out.println("classBSoMessages="
                        + classBSoMessages);
                System.out.println("classBCsMessages="
                        + classBCsMessages);
                System.out.println("classBUnknownMessages="
                        + classBUnknownMessages);
                System.out.println("classBAssignedMessages="
                        + classBAssignedMessages);

                for (Map.Entry<String, IntervalGroup> entry
                        : classBIntervalGroups.entrySet()) {

                    System.out.println(
                            entry.getKey() + "="
                                    + entry.getValue().summary());
                }
            }
        }
    }

    private static class IntervalGroup {

        final long[] histogram = new long[1800];

        long intervals;
        long loss;

        void add(double actualSeconds, long intervalLoss) {

            intervals++;
            loss += intervalLoss;

            int rounded = (int) Math.round(actualSeconds);

            if (rounded >= 0 && rounded < histogram.length) {
                histogram[rounded]++;
            }
        }

        String summary() {

            return "intervals=" + intervals
                    + ", p50=" + percentile(0.50)
                    + ", p75=" + percentile(0.75)
                    + ", p90=" + percentile(0.90)
                    + ", loss=" + loss;
        }

        private int percentile(double fraction) {

            long histogramTotal = Arrays.stream(histogram).sum();
            long target = (long) Math.ceil(histogramTotal * fraction);
            long cumulative = 0;

            for (int seconds = 0; seconds < histogram.length; seconds++) {
                cumulative += histogram[seconds];

                if (cumulative >= target) {
                    return seconds;
                }
            }

            return -1;
        }
    }
}
