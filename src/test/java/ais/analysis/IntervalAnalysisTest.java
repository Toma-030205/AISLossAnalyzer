package ais.analysis;

import ais.domain.AnalysisProfile;
import ais.domain.ClassBReportingMode;
import ais.domain.FreshnessState;
import ais.domain.GeoPosition;
import ais.domain.PositionReport;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.spatial.HaversineDistanceCalculator;
import ais.spatial.Utm53NProjector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class IntervalAnalysisTest {

    private static final Instant START =
            Instant.parse("2026-09-04T00:00:00Z");
    private static final GeoPosition RECEIVER =
            new GeoPosition(34.68, 135.20);

    @Test
    void acceptsUsableIntervalAndRetainsSubsecondPrecision() {
        IntervalEvaluator evaluator = evaluator();

        IntervalEvaluation.Accepted accepted = assertInstanceOf(
                IntervalEvaluation.Accepted.class,
                evaluator.evaluate(
                        report(START, 34.68, 135.20),
                        report(START.plusMillis(10_500), 34.681, 135.201),
                        10.0));

        assertEquals(10.5, accepted.interval().actualSeconds(), 1.0e-12);
    }

    @Test
    void excludesExactlyThirtyMinutesAndNegativeIntervals() {
        IntervalEvaluator evaluator = evaluator();

        assertEquals(IntervalExclusionReason.GAP_30_MINUTES_OR_MORE,
                excludedReason(evaluator.evaluate(
                        report(START, 34.68, 135.20),
                        report(START.plusSeconds(1_800), 34.69, 135.21),
                        10.0)));
        assertEquals(IntervalExclusionReason.NEGATIVE_INTERVAL,
                excludedReason(evaluator.evaluate(
                        report(START, 34.68, 135.20),
                        report(START.minusMillis(1), 34.69, 135.21),
                        10.0)));
    }

    @Test
    void excludesReceiverDistanceJumpOverThirtyKilometers() {
        IntervalEvaluator evaluator = evaluator();

        assertEquals(IntervalExclusionReason.DISTANCE_JUMP_OVER_30_KM,
                excludedReason(evaluator.evaluate(
                        report(START, 34.68, 135.20),
                        report(START.plusSeconds(10), 35.08, 135.20),
                        10.0)));
    }

    @Test
    void acceptsExactlyThirtyKilometerDistanceDifference() {
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("lab"),
                "Laboratory",
                new GeoPosition(0.0, 0.0),
                null,
                null,
                null,
                LocalDate.of(2020, 1, 1),
                null,
                null);
        IntervalEvaluator evaluator = new IntervalEvaluator(
                receiver,
                AnalysisProfile.phaseOneDefaults(),
                (first, second) -> second.longitude());

        assertInstanceOf(
                IntervalEvaluation.Accepted.class,
                evaluator.evaluate(
                        report(START, 34.68, 135.0),
                        report(START.plusSeconds(10), 34.68, 165.0),
                        10.0));
    }

    @Test
    void estimatesMissingTimesAndProjectedPositions() {
        PositionReport start = report(START, 34.68, 135.20);
        PositionReport end = report(
                START.plusSeconds(30), 34.68, 135.23);
        IntervalCandidate interval = new IntervalCandidate(
                start,
                end,
                10.0,
                30.0,
                0.0,
                2.75);

        List<EstimatedPosition> missing = new MissingPositionEstimator(
                new Utm53NProjector()).estimate(interval, 2);

        assertEquals(List.of(
                        START.plusSeconds(10),
                        START.plusSeconds(20)),
                missing.stream()
                        .map(EstimatedPosition::estimatedAt)
                        .toList());
        assertEquals(135.21,
                missing.get(0).position().longitude(),
                0.00001);
        assertEquals(135.22,
                missing.get(1).position().longitude(),
                0.00001);
    }

    @Test
    void calculatesFreshnessViolationTimeAndDisplayState() {
        FreshnessEvaluator evaluator = new FreshnessEvaluator(3.0);
        IntervalCandidate interval = new IntervalCandidate(
                report(START, 34.68, 135.20),
                report(START.plusSeconds(40), 34.69, 135.21),
                10.0,
                40.0,
                0.0,
                1.5);

        FreshnessInterval freshness = evaluator.evaluate(interval);

        assertEquals(START.plusSeconds(30), freshness.staleStart());
        assertEquals(40.0, freshness.observedSeconds());
        assertEquals(10.0, freshness.staleSeconds());
        assertEquals(FreshnessState.NORMAL,
                evaluator.state(START, 10.0, START.plusSeconds(10)));
        assertEquals(FreshnessState.CAUTION,
                evaluator.state(START, 10.0, START.plusSeconds(20)));
        assertEquals(FreshnessState.VIOLATION,
                evaluator.state(START, 10.0, START.plusSeconds(31)));
    }

    private static IntervalExclusionReason excludedReason(
            IntervalEvaluation evaluation) {
        return assertInstanceOf(
                IntervalEvaluation.Excluded.class,
                evaluation).reason();
    }

    private static IntervalEvaluator evaluator() {
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("lab"),
                "Laboratory",
                RECEIVER,
                null,
                null,
                null,
                LocalDate.of(2020, 1, 1),
                null,
                null);
        return new IntervalEvaluator(
                receiver,
                AnalysisProfile.phaseOneDefaults(),
                new HaversineDistanceCalculator());
    }

    private static PositionReport report(
            Instant receivedAt,
            double latitude,
            double longitude) {
        return new PositionReport(
                receivedAt,
                0,
                1,
                431_000_001,
                new GeoPosition(latitude, longitude),
                10.0,
                90.0,
                90.0,
                0,
                ClassBReportingMode.UNKNOWN,
                false);
    }
}
