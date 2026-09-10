package ais.analysis;

import ais.aggregate.AggregateMetric;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.ClassBReportingMode;
import ais.domain.FreshnessState;
import ais.domain.GeoPosition;
import ais.domain.PositionReport;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.domain.VesselClass;
import ais.domain.VesselMetadataUpdate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultAnalysisEngineTest {

    private static final Instant START =
            Instant.parse("2026-09-04T00:00:00Z");
    private static final GeoPosition POSITION =
            new GeoPosition(34.68, 135.20);
    private static final int MMSI = 431_000_001;

    @Test
    void usesExpectedIntervalStoredFromIntervalStart() {
        DefaultAnalysisEngine engine = startedEngine();

        IntervalExcludedEvent first = assertInstanceOf(
                IntervalExcludedEvent.class,
                engine.accept(report(START, 10.0)).getFirst());
        IntervalAnalyzedEvent second = assertInstanceOf(
                IntervalAnalyzedEvent.class,
                engine.accept(report(
                        START.plusSeconds(20),
                        30.0)).getFirst());
        IntervalAnalyzedEvent third = assertInstanceOf(
                IntervalAnalyzedEvent.class,
                engine.accept(report(
                        START.plusSeconds(24),
                        30.0)).getFirst());

        assertEquals(IntervalExclusionReason.FIRST_REPORT, first.reason());
        assertEquals(10.0,
                second.interval().expectedIntervalSeconds());
        assertEquals(1, second.interval().missingCount());
        assertEquals(2.0,
                third.interval().expectedIntervalSeconds());
        assertEquals(1, third.interval().missingCount());
    }

    @Test
    void pauseBoundaryCannotBecomeAisLoss() {
        DefaultAnalysisEngine engine = startedEngine();
        engine.accept(report(START, 10.0));
        engine.accept(report(START.plusSeconds(10), 10.0));

        engine.resetIntervalCursors(START.plusSeconds(100));
        IntervalExcludedEvent afterResume = assertInstanceOf(
                IntervalExcludedEvent.class,
                engine.accept(report(
                        START.plusSeconds(110),
                        10.0)).getFirst());
        engine.accept(report(START.plusSeconds(120), 10.0));

        assertEquals(IntervalExclusionReason.LIVE_PAUSE_BOUNDARY,
                afterResume.reason());
        AnalysisRunSummary summary = engine.complete(
                START.plusSeconds(120));
        assertEquals(2, summary.acceptedIntervalCount());
        assertEquals(1L, summary.excludedIntervalCounts()
                .get(IntervalExclusionReason.LIVE_PAUSE_BOUNDARY));
    }

    @Test
    void mergesType24PartsIntoVesselDisplayMetadata() {
        DefaultAnalysisEngine engine = startedEngine();
        engine.accept(new VesselMetadataUpdate(
                START,
                0,
                24,
                MMSI,
                VesselClass.CLASS_B,
                null,
                null,
                "BAY RUNNER",
                null,
                null,
                null));
        engine.accept(new VesselMetadataUpdate(
                START.plusMillis(1),
                1,
                24,
                MMSI,
                VesselClass.CLASS_B,
                null,
                "JP1234",
                null,
                60,
                null,
                20));
        engine.accept(report(START.plusSeconds(1), 10.0));

        var vessel = engine.snapshot(
                        START.plusSeconds(1),
                        AnalysisFilter.all())
                .vessels()
                .get(MMSI);

        assertNotNull(vessel.metadata());
        assertEquals("BAY RUNNER", vessel.metadata().vesselName());
        assertEquals("JP1234", vessel.metadata().callSign());
        assertEquals(60, vessel.metadata().shipType());
    }

    @Test
    void snapshotExposesFreshnessTrailAndSparseAggregates() {
        DefaultAnalysisEngine engine = startedEngine();
        engine.accept(report(START, 10.0));
        engine.accept(report(START.plusSeconds(40), 10.0));

        AnalysisSnapshot snapshot = engine.snapshot(
                START.plusSeconds(71),
                AnalysisFilter.all());
        var vessel = snapshot.vessels().get(MMSI);

        assertEquals(FreshnessState.VIOLATION, vessel.freshness());
        assertEquals(2, vessel.trail().size());
        assertEquals(1, snapshot.acceptedIntervalCount());
        assertEquals(1,
                snapshot.aggregation().gridMetrics().values()
                        .stream()
                        .map(AggregateMetric::counts)
                        .mapToLong(counts -> counts.observedCount())
                        .sum());
        assertEquals(3,
                snapshot.aggregation().gridMetrics().values()
                        .stream()
                        .map(AggregateMetric::counts)
                        .mapToLong(counts -> counts.missingCount())
                        .sum());
    }

    @Test
    void snapshotAppliesClassFilterToVesselsAndAggregates() {
        DefaultAnalysisEngine engine = startedEngine();
        engine.accept(report(START, 10.0));
        engine.accept(report(START.plusSeconds(10), 10.0));
        engine.accept(classBReport(START, 431_000_002));
        engine.accept(classBReport(
                START.plusSeconds(180),
                431_000_002));

        AnalysisSnapshot classA = engine.snapshot(
                START.plusSeconds(180),
                new AnalysisFilter(
                        Set.of(VesselClass.CLASS_A),
                        Duration.ofMinutes(60)));

        assertEquals(Set.of(MMSI), classA.vessels().keySet());
        assertEquals(Set.of(VesselClass.CLASS_A),
                classA.aggregation().gridMetrics().keySet().stream()
                        .map(key -> key.vesselClass())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(VesselClass.CLASS_A),
                classA.aggregation().distanceMetrics().keySet().stream()
                        .map(key -> key.vesselClass())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void cannotAcceptEventsBeforeBeginOrAfterCompletion() {
        DefaultAnalysisEngine notStarted = new DefaultAnalysisEngine();
        assertThrows(IllegalStateException.class,
                () -> notStarted.accept(report(START, 10.0)));

        DefaultAnalysisEngine completed = startedEngine();
        completed.complete(START);
        assertThrows(IllegalStateException.class,
                () -> completed.accept(report(START, 10.0)));
    }

    private static DefaultAnalysisEngine startedEngine() {
        DefaultAnalysisEngine engine = new DefaultAnalysisEngine();
        engine.begin(new AnalysisContext(
                receiver(),
                AnalysisProfile.phaseOneDefaults(),
                SourceMode.HISTORICAL,
                AnalysisRunId.parse(
                        "00000000-0000-0000-0000-000000000001"),
                START));
        return engine;
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("lab"),
                "Laboratory",
                POSITION,
                30.0,
                null,
                null,
                LocalDate.of(2020, 1, 1),
                null,
                null);
    }

    private static PositionReport report(Instant time, double sog) {
        return new PositionReport(
                time,
                time.equals(START) ? 0 : 1,
                1,
                MMSI,
                POSITION,
                sog,
                90.0,
                90.0,
                0,
                ClassBReportingMode.UNKNOWN,
                false);
    }

    private static PositionReport classBReport(Instant time, int mmsi) {
        return new PositionReport(
                time,
                time.equals(START) ? 0 : 1,
                18,
                mmsi,
                POSITION,
                1.0,
                90.0,
                90.0,
                null,
                ClassBReportingMode.SELF_ORGANIZING,
                false);
    }
}
