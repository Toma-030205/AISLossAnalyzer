package ais.analysis;

import ais.aggregate.SessionAggregator;
import ais.domain.AnalysisContext;
import ais.domain.NormalizedAisEvent;
import ais.domain.PositionReport;
import ais.domain.VesselMetadata;
import ais.domain.VesselMetadataUpdate;
import ais.spatial.CoordinateProjector;
import ais.spatial.DistanceCalculator;
import ais.spatial.HaversineDistanceCalculator;
import ais.spatial.ProjectedPoint;
import ais.spatial.Utm53NProjector;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultAnalysisEngine implements AnalysisEngine {

    private final CoordinateProjector projector;
    private final DistanceCalculator distanceCalculator;
    private final VesselStateStore vesselStates = new VesselStateStore();
    private final IntervalCursorStore intervalCursors =
            new IntervalCursorStore();
    private final EnumMap<IntervalExclusionReason, Long> exclusions =
            new EnumMap<>(IntervalExclusionReason.class);
    private final AnalysisSnapshotFactory snapshotFactory =
            new AnalysisSnapshotFactory();

    private AnalysisContext context;
    private SpecificationReportRatePolicy reportRatePolicy;
    private IntervalEvaluator intervalEvaluator;
    private MissingPositionEstimator missingPositionEstimator;
    private FreshnessEvaluator freshnessEvaluator;
    private SessionAggregator aggregator;
    private boolean completed;
    private long acceptedIntervalCount;
    private long estimatedMissingCount;
    private long metadataUpdateCount;

    public DefaultAnalysisEngine() {
        this(new Utm53NProjector(), new HaversineDistanceCalculator());
    }

    public DefaultAnalysisEngine(
            CoordinateProjector projector,
            DistanceCalculator distanceCalculator) {
        this.projector = Objects.requireNonNull(projector, "projector");
        this.distanceCalculator = Objects.requireNonNull(
                distanceCalculator,
                "distanceCalculator");
    }

    @Override
    public void begin(AnalysisContext context) {
        Objects.requireNonNull(context, "context");
        if (this.context != null) {
            throw new IllegalStateException(
                    "analysis engine has already been started");
        }
        this.context = context;
        reportRatePolicy = new SpecificationReportRatePolicy(
                context.analysisProfile());
        intervalEvaluator = new IntervalEvaluator(
                context.receiverProfile(),
                context.analysisProfile(),
                distanceCalculator);
        missingPositionEstimator = new MissingPositionEstimator(projector);
        freshnessEvaluator = new FreshnessEvaluator(
                context.analysisProfile().freshnessMultiplier());
        aggregator = new SessionAggregator(
                context.receiverProfile(),
                context.analysisProfile(),
                projector,
                distanceCalculator);
    }

    @Override
    public List<AnalysisEvent> accept(NormalizedAisEvent event) {
        requireRunning();
        Objects.requireNonNull(event, "event");
        if (event instanceof VesselMetadataUpdate metadataUpdate) {
            VesselMetadata metadata = vesselStates.accept(metadataUpdate);
            metadataUpdateCount++;
            return List.of(new VesselMetadataUpdatedEvent(metadata));
        }
        if (event instanceof PositionReport report) {
            return acceptPosition(report);
        }
        throw new IllegalArgumentException(
                "unsupported normalized event: " + event.getClass());
    }

    @Override
    public void resetIntervalCursors(Instant resumedAt) {
        requireRunning();
        Objects.requireNonNull(resumedAt, "resumedAt");
        intervalCursors.resetForPause();
        vesselStates.resetContinuity();
    }

    @Override
    public AnalysisSnapshot snapshot(
            Instant displayTime,
            AnalysisFilter filter) {
        requireStarted();
        Objects.requireNonNull(displayTime, "displayTime");
        Objects.requireNonNull(filter, "filter");
        return snapshotFactory.create(
                context,
                displayTime,
                filter,
                vesselStates,
                freshnessEvaluator,
                aggregator.snapshot(),
                acceptedIntervalCount,
                Map.copyOf(exclusions));
    }

    @Override
    public AnalysisRunSummary checkpoint(Instant at) {
        requireRunning();
        Objects.requireNonNull(at, "at");
        if (at.isBefore(context.startedAt())) {
            throw new IllegalArgumentException(
                    "analysis end must not precede its start");
        }
        return new AnalysisRunSummary(
                context,
                at,
                acceptedIntervalCount,
                estimatedMissingCount,
                metadataUpdateCount,
                Map.copyOf(exclusions),
                aggregator.snapshot());
    }

    @Override
    public AnalysisRunSummary complete(Instant endedAt) {
        AnalysisRunSummary summary = checkpoint(endedAt);
        completed = true;
        return summary;
    }

    private List<AnalysisEvent> acceptPosition(PositionReport report) {
        VesselAnalysisState vesselState = vesselStates.stateFor(report.mmsi());
        boolean changingCourse = vesselState.updateCourse(report);
        double expectedForCurrent = reportRatePolicy
                .expectedIntervalSeconds(report, changingCourse);
        var previous = intervalCursors.previous(report);

        if (previous.isEmpty()) {
            vesselState.recordPosition(report, expectedForCurrent);
            IntervalExclusionReason reason =
                    intervalCursors.firstReason(report);
            intervalCursors.put(report, expectedForCurrent);
            recordExclusion(reason);
            return List.of(new IntervalExcludedEvent(report, reason));
        }

        IntervalEvaluation evaluation = intervalEvaluator.evaluate(
                previous.get().report(),
                report,
                previous.get().expectedIntervalSeconds());

        if (evaluation instanceof IntervalEvaluation.Excluded excluded) {
            vesselState.resetContinuity();
            changingCourse = vesselState.updateCourse(report);
            expectedForCurrent = reportRatePolicy
                    .expectedIntervalSeconds(report, changingCourse);
            vesselState.recordPosition(report, expectedForCurrent);
            intervalCursors.put(report, expectedForCurrent);
            recordExclusion(excluded.reason());
            return List.of(new IntervalExcludedEvent(
                    report,
                    excluded.reason()));
        }

        IntervalCandidate candidate =
                ((IntervalEvaluation.Accepted) evaluation).interval();
        long missingCount = LossEstimator.estimateMissingMessages(
                candidate.actualSeconds(),
                candidate.expectedIntervalSeconds());
        var missingPositions = missingPositionEstimator.estimate(
                candidate,
                missingCount);
        var freshness = freshnessEvaluator.evaluate(candidate);
        ProjectedPoint startProjected = projector.project(
                candidate.start().position());
        ProjectedPoint endProjected = projector.project(
                candidate.end().position());
        AnalyzedInterval analyzed = new AnalyzedInterval(
                candidate.start(),
                candidate.end(),
                candidate.expectedIntervalSeconds(),
                candidate.actualSeconds(),
                candidate.startDistanceKilometers(),
                candidate.endDistanceKilometers(),
                missingCount,
                missingPositions,
                freshness,
                startProjected,
                endProjected);
        aggregator.accept(analyzed);
        acceptedIntervalCount++;
        estimatedMissingCount = Math.addExact(
                estimatedMissingCount,
                missingCount);

        vesselState.recordPosition(report, expectedForCurrent);
        intervalCursors.put(report, expectedForCurrent);
        return List.of(new IntervalAnalyzedEvent(analyzed));
    }

    private void recordExclusion(IntervalExclusionReason reason) {
        exclusions.merge(reason, 1L, Math::addExact);
    }

    private void requireStarted() {
        if (context == null) {
            throw new IllegalStateException(
                    "analysis engine has not been started");
        }
    }

    private void requireRunning() {
        requireStarted();
        if (completed) {
            throw new IllegalStateException(
                    "analysis engine has already been completed");
        }
    }
}
