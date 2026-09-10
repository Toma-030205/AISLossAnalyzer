package ais.aggregate;

import ais.analysis.AnalyzedInterval;
import ais.analysis.EstimatedPosition;
import ais.domain.AnalysisProfile;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.VesselClass;
import ais.spatial.CoordinateProjector;
import ais.spatial.DistanceBand;
import ais.spatial.DistanceBandDefinition;
import ais.spatial.DistanceCalculator;
import ais.spatial.DistanceSegmentAllocator;
import ais.spatial.DistanceTimeAllocation;
import ais.spatial.GridCellId;
import ais.spatial.GridDefinition;
import ais.spatial.GridSegmentAllocator;
import ais.spatial.GridTimeAllocation;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

public final class SessionAggregator {

    public static final ZoneId DEFAULT_AGGREGATION_ZONE =
            ZoneId.of("Asia/Tokyo");

    private final CoordinateProjector projector;
    private final DistanceCalculator distanceCalculator;
    private final GeoPosition receiverPosition;
    private final GridDefinition grid;
    private final GridSegmentAllocator gridAllocator;
    private final DistanceBandDefinition distanceBands;
    private final DistanceSegmentAllocator distanceAllocator;
    private final FiveMinuteBucketizer bucketizer;
    private final GridMetricAccumulator gridMetrics =
            new GridMetricAccumulator();
    private final DistanceMetricAccumulator distanceMetrics =
            new DistanceMetricAccumulator();

    private long outsideDistanceRangeCount;

    public SessionAggregator(
            ReceiverProfile receiver,
            AnalysisProfile profile,
            CoordinateProjector projector,
            DistanceCalculator distanceCalculator) {
        this(
                receiver,
                profile,
                projector,
                distanceCalculator,
                DEFAULT_AGGREGATION_ZONE);
    }

    public SessionAggregator(
            ReceiverProfile receiver,
            AnalysisProfile profile,
            CoordinateProjector projector,
            DistanceCalculator distanceCalculator,
            ZoneId aggregationZone) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(profile, "profile");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.distanceCalculator = Objects.requireNonNull(
                distanceCalculator,
                "distanceCalculator");
        receiverPosition = receiver.position();
        grid = new GridDefinition(
                GridCellId.UTM_ZONE_53_NORTH,
                profile.gridOriginEasting(),
                profile.gridOriginNorthing(),
                profile.gridSizeMeters());
        gridAllocator = new GridSegmentAllocator(grid);
        distanceBands = new DistanceBandDefinition(
                profile.distanceBinKilometers(),
                profile.maximumDistanceKilometers());
        distanceAllocator = new DistanceSegmentAllocator(
                distanceBands,
                projector,
                distanceCalculator,
                receiverPosition);
        bucketizer = new FiveMinuteBucketizer(aggregationZone);
    }

    public void accept(AnalyzedInterval interval) {
        Objects.requireNonNull(interval, "interval");
        VesselClass vesselClass = interval.start().vesselClass();
        int mmsi = interval.start().mmsi();

        addObserved(
                interval.start().receivedAt(),
                interval.startProjected(),
                interval.startDistanceKilometers(),
                vesselClass,
                mmsi);
        for (EstimatedPosition missing : interval.missingPositions()) {
            addMissing(
                    missing.estimatedAt(),
                    missing.position(),
                    missing.projectedPoint(),
                    vesselClass,
                    mmsi);
        }

        if (interval.end().receivedAt().isAfter(
                interval.start().receivedAt())) {
            allocateGridDuration(interval, vesselClass, mmsi);
            allocateDistanceDuration(interval, vesselClass, mmsi);
        }
    }

    public AggregationSnapshot snapshot() {
        return new AggregationSnapshot(
                gridMetrics.snapshot(),
                distanceMetrics.snapshot(),
                outsideDistanceRangeCount);
    }

    public void clear() {
        gridMetrics.clear();
        distanceMetrics.clear();
        outsideDistanceRangeCount = 0;
    }

    public GridDefinition gridDefinition() {
        return grid;
    }

    public DistanceBandDefinition distanceBandDefinition() {
        return distanceBands;
    }

    private void addObserved(
            Instant at,
            ais.spatial.ProjectedPoint projected,
            double distanceKilometers,
            VesselClass vesselClass,
            int mmsi) {
        Instant bucket = bucketizer.bucketStart(at);
        gridMetrics.addObserved(
                bucket,
                grid.cellFor(projected),
                vesselClass,
                mmsi);
        distanceBands.bandFor(distanceKilometers)
                .ifPresentOrElse(
                        band -> distanceMetrics.addObserved(
                                bucket,
                                band,
                                vesselClass,
                                mmsi),
                        () -> outsideDistanceRangeCount++);
    }

    private void addMissing(
            Instant at,
            GeoPosition position,
            ais.spatial.ProjectedPoint projected,
            VesselClass vesselClass,
            int mmsi) {
        Instant bucket = bucketizer.bucketStart(at);
        gridMetrics.addMissing(
                bucket,
                grid.cellFor(projected),
                vesselClass,
                mmsi);
        double distance = distanceCalculator.distanceKilometers(
                receiverPosition,
                position);
        distanceBands.bandFor(distance)
                .ifPresentOrElse(
                        band -> distanceMetrics.addMissing(
                                bucket,
                                band,
                                vesselClass,
                                mmsi),
                        () -> outsideDistanceRangeCount++);
    }

    private void allocateGridDuration(
            AnalyzedInterval interval,
            VesselClass vesselClass,
            int mmsi) {
        for (GridTimeAllocation allocation : gridAllocator.allocate(
                interval.startProjected(),
                interval.endProjected(),
                interval.start().receivedAt(),
                interval.end().receivedAt())) {
            for (TimeAllocation time : bucketizer.split(
                    allocation.startAt(),
                    allocation.endAt())) {
                gridMetrics.addDuration(
                        time.bucketStart(),
                        allocation.cell(),
                        vesselClass,
                        mmsi,
                        time.seconds(),
                        staleSeconds(time, interval.freshness().staleStart()));
            }
        }
    }

    private void allocateDistanceDuration(
            AnalyzedInterval interval,
            VesselClass vesselClass,
            int mmsi) {
        for (DistanceTimeAllocation allocation : distanceAllocator.allocate(
                interval.startProjected(),
                interval.endProjected(),
                interval.start().receivedAt(),
                interval.end().receivedAt())) {
            for (TimeAllocation time : bucketizer.split(
                    allocation.startAt(),
                    allocation.endAt())) {
                distanceMetrics.addDuration(
                        time.bucketStart(),
                        allocation.band(),
                        vesselClass,
                        mmsi,
                        time.seconds(),
                        staleSeconds(time, interval.freshness().staleStart()));
            }
        }
    }

    private static double staleSeconds(
            TimeAllocation allocation,
            Instant staleStart) {
        Instant overlapStart = allocation.startAt().isAfter(staleStart)
                ? allocation.startAt()
                : staleStart;
        if (!overlapStart.isBefore(allocation.endAt())) {
            return 0.0;
        }
        return Duration.between(overlapStart, allocation.endAt())
                .toNanos() / 1_000_000_000.0;
    }
}
