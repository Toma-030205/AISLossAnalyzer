package ais.stats;

import ais.logic.AisAnalysisRules;
import ais.model.AisMessage;
import ais.model.Vessel;

import java.time.LocalDateTime;

public class AisStatisticsTest {

    private static final LocalDateTime BASE_TIME =
            LocalDateTime.of(2026, 1, 1, 0, 0);

    public static void main(String[] args) {

        testThresholds();
        testSameCategoryIntervals();
        testClassAPositionGrouping();
        testTrackGapBoundaries();
        testBatchAndStreamingConsistency();

        System.out.println("All AIS statistics tests passed.");
    }

    private static void testThresholds() {

        assertEquals(
                1800,
                AisAnalysisRules.getTrackGapThresholdSeconds(1),
                "Class A track gap threshold");

        assertEquals(
                3600,
                AisAnalysisRules.getTrackGapThresholdSeconds(5),
                "Type 5 track gap threshold");

        assertEquals(
                1800,
                AisAnalysisRules.getTrackGapThresholdSeconds(18),
                "Class B track gap threshold");
    }

    private static void testSameCategoryIntervals() {

        StreamingVesselStatistics statistics =
                new StreamingVesselStatistics();

        statistics.accept(position(1, 100000001, 0, 5.0));
        statistics.accept(message(5, 100000001, 5));
        statistics.accept(position(1, 100000001, 100, 5.0));

        VesselStatisticsResult classA =
                resultFor(statistics, 1);

        assertEquals(1, classA.totalMessages,
                "Class A valid interval count");
        assertEquals(9, classA.totalEstimatedLoss,
                "Class A loss across an interleaved Type 5 message");

        VesselStatisticsResult type5 =
                resultFor(statistics, 5);

        assertEquals(0, type5.totalMessages,
                "A lone Type 5 message must not form an interval");
    }

    private static void testClassAPositionGrouping() {

        StreamingVesselStatistics statistics =
                new StreamingVesselStatistics();

        statistics.accept(position(1, 100000002, 0, 5.0));
        statistics.accept(position(2, 100000002, 10, 5.0));
        statistics.accept(position(3, 100000002, 20, 5.0));

        VesselStatisticsResult result =
                resultFor(statistics, 1);

        assertEquals(2, result.totalMessages,
                "Types 1, 2 and 3 must share one Class A series");
        assertEquals(0, result.totalEstimatedLoss,
                "Normal Class A intervals");
    }

    private static void testTrackGapBoundaries() {

        StreamingVesselStatistics includedClassA =
                new StreamingVesselStatistics();

        includedClassA.accept(
                anchoredPosition(1, 100000003, 0));
        includedClassA.accept(
                anchoredPosition(1, 100000003, 1799));

        assertEquals(
                1,
                resultFor(includedClassA, 1).totalMessages,
                "1799-second Class A interval must be included");

        StreamingVesselStatistics excludedClassA =
                new StreamingVesselStatistics();

        excludedClassA.accept(
                anchoredPosition(1, 100000004, 0));
        excludedClassA.accept(
                anchoredPosition(1, 100000004, 1800));

        assertEquals(
                0,
                resultFor(excludedClassA, 1).totalMessages,
                "1800-second Class A interval must be a track gap");

        StreamingVesselStatistics includedType5 =
                new StreamingVesselStatistics();

        includedType5.accept(message(5, 100000005, 0));
        includedType5.accept(message(5, 100000005, 3599));

        assertEquals(
                1,
                resultFor(includedType5, 5).totalMessages,
                "3599-second Type 5 interval must be included");

        StreamingVesselStatistics excludedType5 =
                new StreamingVesselStatistics();

        excludedType5.accept(message(5, 100000006, 0));
        excludedType5.accept(message(5, 100000006, 3600));

        assertEquals(
                0,
                resultFor(excludedType5, 5).totalMessages,
                "3600-second Type 5 interval must be a track gap");
    }

    private static void testBatchAndStreamingConsistency() {

        Vessel vessel = new Vessel(100000007);
        StreamingVesselStatistics streaming =
                new StreamingVesselStatistics();

        AisMessage[] messages = {
                position(1, 100000007, 0, 5.0),
                message(5, 100000007, 5),
                position(2, 100000007, 30, 5.0),
                position(3, 100000007, 60, 5.0),
                message(5, 100000007, 365)
        };

        for (AisMessage message : messages) {
            vessel.addMessage(message);
            streaming.accept(message);
        }

        vessel.sortMessagesByTime();

        VesselStatistics analyzer =
                new VesselStatistics();

        assertSameResult(
                resultFor(streaming, 1),
                analyzer.analyze(vessel, 1),
                "Class A batch/streaming consistency");

        assertSameResult(
                resultFor(streaming, 5),
                analyzer.analyze(vessel, 5),
                "Type 5 batch/streaming consistency");
    }

    private static AisMessage anchoredPosition(
            int type,
            int mmsi,
            long seconds) {

        AisMessage message =
                position(type, mmsi, seconds, 0.0);

        message.navStatus = 1;
        return message;
    }

    private static AisMessage position(
            int type,
            int mmsi,
            long seconds,
            double sog) {

        AisMessage message =
                message(type, mmsi, seconds);

        message.lat = 34.7;
        message.lon = 135.3;
        message.sog = sog;
        message.cog = 90.0;

        return message;
    }

    private static AisMessage message(
            int type,
            int mmsi,
            long seconds) {

        AisMessage message = new AisMessage();
        message.messageType = type;
        message.mmsi = mmsi;
        message.timestamp = BASE_TIME.plusSeconds(seconds);
        return message;
    }

    private static VesselStatisticsResult resultFor(
            StreamingVesselStatistics statistics,
            int targetType) {

        return statistics.getResults(targetType, 0).get(0);
    }

    private static void assertSameResult(
            VesselStatisticsResult expected,
            VesselStatisticsResult actual,
            String label) {

        assertEquals(
                expected.totalMessages,
                actual.totalMessages,
                label + " observed");

        assertEquals(
                expected.totalEstimatedLoss,
                actual.totalEstimatedLoss,
                label + " loss");

        assertEquals(
                expected.maxDelta,
                actual.maxDelta,
                label + " max delta");
    }

    private static void assertEquals(
            long expected,
            long actual,
            String label) {

        if (expected != actual) {
            throw new AssertionError(
                    label
                            + ": expected " + expected
                            + " but was " + actual);
        }
    }
}
