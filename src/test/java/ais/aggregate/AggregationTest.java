package ais.aggregate;

import ais.analysis.AnalyzedInterval;
import ais.analysis.EstimatedPosition;
import ais.analysis.FreshnessInterval;
import ais.analysis.IntervalCandidate;
import ais.analysis.MissingPositionEstimator;
import ais.domain.AnalysisProfile;
import ais.domain.ClassBReportingMode;
import ais.domain.GeoPosition;
import ais.domain.PositionReport;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;
import ais.spatial.HaversineDistanceCalculator;
import ais.spatial.ProjectedPoint;
import ais.spatial.Utm53NProjector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregationTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final GeoPosition RECEIVER_POSITION =
            new GeoPosition(34.68, 135.20);

    @Test
    void splitsObservedAndStaleTimeAtFiveMinuteBoundaries() {
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        ReceiverProfile receiver = receiver();
        Utm53NProjector projector = new Utm53NProjector();
        HaversineDistanceCalculator distances =
                new HaversineDistanceCalculator();
        SessionAggregator aggregator = new SessionAggregator(
                receiver,
                profile,
                projector,
                distances);
        Instant startAt = ZonedDateTime.of(
                2026, 9, 4, 0, 3, 0, 0, JST).toInstant();
        Instant endAt = startAt.plusSeconds(400);
        PositionReport start = report(startAt, RECEIVER_POSITION);
        PositionReport end = report(endAt, RECEIVER_POSITION);
        IntervalCandidate candidate = new IntervalCandidate(
                start, end, 100.0, 400.0, 0.0, 0.0);
        List<EstimatedPosition> missing =
                new MissingPositionEstimator(projector).estimate(candidate, 3);
        ProjectedPoint projected = projector.project(RECEIVER_POSITION);

        aggregator.accept(new AnalyzedInterval(
                start,
                end,
                100.0,
                400.0,
                0.0,
                0.0,
                3,
                missing,
                new FreshnessInterval(
                        300.0,
                        startAt.plusSeconds(300),
                        400.0,
                        100.0),
                projected,
                projected));

        AggregationSnapshot snapshot = aggregator.snapshot();
        assertEquals(2, snapshot.gridMetrics().size());
        List<AggregateMetric> ordered = snapshot.gridMetrics().entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey((first, second) ->
                        first.bucketStart().compareTo(second.bucketStart())))
                .map(Map.Entry::getValue)
                .toList();
        assertEquals(new MetricCounts(1, 1, 120.0, 0.0),
                ordered.get(0).counts());
        assertEquals(new MetricCounts(0, 2, 280.0, 100.0),
                ordered.get(1).counts());
        assertEquals(1, ordered.get(0).distinctVesselCount());

        long distanceObserved = snapshot.distanceMetrics().values()
                .stream()
                .mapToLong(metric -> metric.counts().observedCount())
                .sum();
        long distanceMissing = snapshot.distanceMetrics().values()
                .stream()
                .mapToLong(metric -> metric.counts().missingCount())
                .sum();
        assertEquals(1, distanceObserved);
        assertEquals(3, distanceMissing);
        assertEquals(1, snapshot.distanceVesselDayMetrics().size());
        Map.Entry<DistanceVesselDayKey, MetricCounts> vesselDay =
                snapshot.distanceVesselDayMetrics().entrySet()
                        .iterator().next();
        assertEquals(LocalDate.of(2026, 9, 4),
                vesselDay.getKey().observedDate());
        assertEquals(431_000_001, vesselDay.getKey().mmsi());
        assertEquals(new MetricCounts(1, 3, 400.0, 100.0),
                vesselDay.getValue());
        assertEquals(0, snapshot.outsideDistanceRangeCount());
    }

    @Test
    void calculatesRatesButMarksSmallSamplesAsInsufficient() {
        MetricCalculator calculator = new MetricCalculator(
                AnalysisProfile.phaseOneDefaults());
        AggregateMetric metric = new AggregateMetric(
                new MetricCounts(20, 10, 100.0, 25.0),
                Set.of(431_000_001, 431_000_002));

        MetricEvaluation evaluation = calculator.evaluate(metric);

        assertEquals(33.333333,
                evaluation.lossRatePercent(),
                0.000001);
        assertEquals(25.0,
                evaluation.freshnessViolationRatePercent());
        assertFalse(evaluation.hasSufficientData());
        assertEquals(Set.of(
                        InsufficientDataReason
                                .DISTINCT_VESSELS_BELOW_MINIMUM),
                evaluation.insufficientReasons());
    }

    @Test
    void acceptsMinimumExpectedCountAndThreeDistinctVessels() {
        MetricCalculator calculator = new MetricCalculator(
                AnalysisProfile.phaseOneDefaults());
        AggregateMetric metric = new AggregateMetric(
                new MetricCounts(30, 0, 300.0, 0.0),
                Set.of(431_000_001, 431_000_002, 431_000_003));

        MetricEvaluation evaluation = calculator.evaluate(metric);

        assertTrue(evaluation.hasSufficientData());
        assertEquals(0.0, evaluation.lossRatePercent());
        assertEquals(0.0, evaluation.freshnessViolationRatePercent());
    }

    @Test
    void rollsFiveMinuteMetricsIntoCalendarYearAndHour() {
        DistanceBand band = new DistanceBand(0, 0.0, 5.0);
        Instant january = ZonedDateTime.of(
                2026, 1, 10, 8, 0, 0, 0, JST).toInstant();
        Instant december = ZonedDateTime.of(
                2026, 12, 20, 8, 5, 0, 0, JST).toInstant();
        Map<AggregateKey<DistanceBand>, AggregateMetric> metrics = Map.of(
                new AggregateKey<>(
                        january, band, VesselClass.CLASS_A),
                new AggregateMetric(
                        new MetricCounts(10, 2, 300.0, 30.0),
                        Set.of(431_000_001)),
                new AggregateKey<>(
                        december, band, VesselClass.CLASS_A),
                new AggregateMetric(
                        new MetricCounts(20, 3, 300.0, 60.0),
                        Set.of(431_000_001, 431_000_002)));
        PeriodRollupService rollups = new PeriodRollupService(JST);

        RollupMetric annual = rollups.rollup(
                        metrics,
                        RollupDimension.YEAR)
                .values()
                .iterator()
                .next();
        RollupMetric hour = rollups.rollup(
                        metrics,
                        RollupDimension.HOUR_OF_DAY)
                .values()
                .iterator()
                .next();

        assertEquals(new MetricCounts(30, 5, 600.0, 90.0),
                annual.metric().counts());
        assertEquals(2, annual.metric().distinctVesselCount());
        assertEquals(2, annual.observationDayCount());
        assertEquals(new MetricCounts(30, 5, 600.0, 90.0),
                hour.metric().counts());
        assertTrue(rollups.rollup(metrics, RollupDimension.MONTH)
                .keySet()
                .stream()
                .anyMatch(key -> key.periodValue().equals("2026-12")));
        assertEquals(2,
                rollups.rollup(metrics, RollupDimension.DAY).size());
        assertEquals(2,
                rollups.rollup(metrics, RollupDimension.DAY_OF_WEEK)
                        .size());
    }

    @Test
    void countsObservedPointBeyondSeventyKilometersAsOutsideRange() {
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        ReceiverProfile receiver = receiver();
        Utm53NProjector projector = new Utm53NProjector();
        PositionReport start = report(
                Instant.parse("2026-09-04T00:00:00Z"),
                new GeoPosition(35.40, 135.20));
        ProjectedPoint projected = projector.project(start.position());
        SessionAggregator aggregator = new SessionAggregator(
                receiver,
                profile,
                projector,
                new HaversineDistanceCalculator());

        aggregator.accept(new AnalyzedInterval(
                start,
                start,
                10.0,
                0.0,
                80.0,
                80.0,
                0,
                List.of(),
                new FreshnessInterval(
                        30.0,
                        start.receivedAt().plusSeconds(30),
                        0.0,
                        0.0),
                projected,
                projected));

        assertEquals(1,
                aggregator.snapshot().outsideDistanceRangeCount());
        assertTrue(aggregator.snapshot().distanceMetrics().isEmpty());
        assertEquals(1, aggregator.snapshot().gridMetrics().size());
    }

    @Test
    void usesJapaneseLocalTimeForFiveMinuteBuckets() {
        FiveMinuteBucketizer bucketizer = new FiveMinuteBucketizer(JST);
        Instant input = ZonedDateTime.of(
                2026, 9, 4, 23, 58, 30, 0, JST).toInstant();

        List<TimeAllocation> slices = bucketizer.split(
                input,
                input.plusSeconds(180));

        assertEquals(2, slices.size());
        assertEquals(90.0, slices.get(0).seconds());
        assertEquals(90.0, slices.get(1).seconds());
        assertEquals(LocalDate.of(2026, 9, 5),
                slices.get(1).bucketStart().atZone(JST).toLocalDate());
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("lab"),
                "Laboratory",
                RECEIVER_POSITION,
                null,
                null,
                null,
                LocalDate.of(2020, 1, 1),
                null,
                null);
    }

    private static PositionReport report(
            Instant receivedAt,
            GeoPosition position) {
        return new PositionReport(
                receivedAt,
                0,
                1,
                431_000_001,
                position,
                10.0,
                90.0,
                90.0,
                0,
                ClassBReportingMode.UNKNOWN,
                false);
    }
}
