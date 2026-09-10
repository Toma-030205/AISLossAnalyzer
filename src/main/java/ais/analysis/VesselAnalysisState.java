package ais.analysis;

import ais.domain.PositionReport;
import ais.domain.VesselMetadata;

final class VesselAnalysisState {

    private final CourseChangeTracker courseChangeTracker =
            new CourseChangeTracker();
    private final TrailBuffer trail = new TrailBuffer();

    private PositionReport latestPosition;
    private VesselMetadata metadata;
    private double latestExpectedIntervalSeconds = Double.NaN;

    boolean updateCourse(PositionReport report) {
        return courseChangeTracker.accept(report);
    }

    void recordPosition(
            PositionReport report,
            double expectedIntervalSeconds) {
        latestPosition = report;
        latestExpectedIntervalSeconds = expectedIntervalSeconds;
        trail.accept(report);
    }

    VesselMetadata mergeMetadata(ais.domain.VesselMetadataUpdate update) {
        metadata = metadata == null
                ? VesselMetadata.from(update)
                : metadata.merge(update);
        return metadata;
    }

    void resetContinuity() {
        courseChangeTracker.reset();
    }

    PositionReport latestPosition() {
        return latestPosition;
    }

    VesselMetadata metadata() {
        return metadata;
    }

    double latestExpectedIntervalSeconds() {
        return latestExpectedIntervalSeconds;
    }

    TrailBuffer trail() {
        return trail;
    }
}
